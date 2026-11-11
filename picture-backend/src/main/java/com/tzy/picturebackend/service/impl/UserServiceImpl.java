package com.tzy.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.model.dto.user.UserQueryRequest;
import com.tzy.picturebackend.model.dto.user.UserUpdateProfileRequest;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.UserRoleEnum;
import com.tzy.picturebackend.model.vo.LoginUserVO;
import com.tzy.picturebackend.model.vo.UserVO;
import com.tzy.picturebackend.service.UserService;
import com.tzy.picturebackend.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.tzy.picturebackend.constant.UserConstant.USER_LOGIN_STATE;

/**
* @author shifang37
* @description 针对表【user(用户)】的数据库操作Service实现
* @createDate 2026-03-05 21:32:07
*/
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{

    /**
     * 密码编码器：BCrypt 自带随机盐，同一明文每次加密结果不同
     */
    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    /**
     * 历史遗留密码格式：MD5(固定盐 + 明文)，32 位十六进制
     */
    private static final Pattern LEGACY_MD5_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");

    /**
     * 历史遗留的固定盐，仅用于校验存量密码
     */
    private static final String LEGACY_SALT = "yupi";

    /**
     * 允许排序的字段白名单：sortField 会被原样拼进 ORDER BY，不能直接使用客户端传入的值。
     * 不含 userPassword，避免通过排序结果推断密文
     */
    static final Set<String> SORT_FIELD_WHITELIST = Set.of(
            "id", "userAccount", "userName", "userRole", "createTime", "editTime", "updateTime");


    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        //1.校验
        if(StrUtil.hasBlank(userAccount,userPassword,checkPassword)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"参数为空");
        }
        if(userAccount.length()<4){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"账号过短");
        }
        if(userPassword.length()<6||checkPassword.length()<6){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"密码过短");
        }
        if(!checkPassword.equals(userPassword)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"密码不一致");
        }
        //2.检查是否重复
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount",userAccount);
        long count = this.baseMapper.selectCount(queryWrapper);
        if(count>0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"账号重复");
        }
        //3.加密
        String encryptPassword = getEncryptPassword(userPassword);
        //4.插入数据
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        user.setUserName("无名");
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean saveResult = this.save(user);
        if(!saveResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"注册失败，数据库错误");
        }
        return user.getId();
    }

    @Override
    public String getEncryptPassword(String userPassword) {
        return PASSWORD_ENCODER.encode(userPassword);
    }

    /**
     * 校验密码，兼容历史遗留的 MD5 密码
     *
     * @param rawPassword       明文密码
     * @param encryptedPassword 库中存储的密文
     * @return 是否匹配
     */
    private boolean isPasswordMatch(String rawPassword, String encryptedPassword) {
        if (StrUtil.isBlank(encryptedPassword)) {
            return false;
        }
        if (isLegacyPassword(encryptedPassword)) {
            String legacy = DigestUtils.md5DigestAsHex(
                    (LEGACY_SALT + rawPassword).getBytes(StandardCharsets.UTF_8));
            return legacy.equalsIgnoreCase(encryptedPassword);
        }
        return PASSWORD_ENCODER.matches(rawPassword, encryptedPassword);
    }

    /**
     * 是否为历史遗留的 MD5 密码（32 位十六进制）
     */
    private boolean isLegacyPassword(String encryptedPassword) {
        return encryptedPassword != null && LEGACY_MD5_PATTERN.matcher(encryptedPassword).matches();
    }

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        //1.校验
        if(StrUtil.hasBlank(userAccount,userPassword)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"参数为空");
        }
        if(userAccount.length()<4){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"账号错误");
        }
        if(userPassword.length()<6){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"密码错误");
        }
        //2.查询用户
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount",userAccount);
        User user = this.baseMapper.selectOne(queryWrapper);
        if(user==null || !isPasswordMatch(userPassword, user.getUserPassword())){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"用户不存在或密码错误");
        }
        //3.存量 MD5 密码在登录成功后自动升级为 BCrypt
        if(isLegacyPassword(user.getUserPassword())){
            User upgrade = new User();
            upgrade.setId(user.getId());
            upgrade.setUserPassword(getEncryptPassword(userPassword));
            this.updateById(upgrade);
            user.setUserPassword(upgrade.getUserPassword());
        }

        //4.记录登录状态：登录成功后更换 session id，防止会话固定攻击
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(USER_LOGIN_STATE, user);
        return this.getLoginUserVO(user);
    }

    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if(user==null){
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtils.copyProperties(user,loginUserVO);
        return loginUserVO;
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if(currentUser==null){
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        long userId = currentUser.getId();
        currentUser = this.getById(userId);
        if(currentUser==null){
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    @Override
    public User getLoginUserOrNull(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        User currentUser = (User) session.getAttribute(USER_LOGIN_STATE);
        if (currentUser == null || currentUser.getId() == null) {
            return null;
        }
        return this.getById(currentUser.getId());
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if(currentUser==null){
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"未登录");
        }
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return true;
    }

    @Override
    public boolean updatePassword(User loginUser, String oldPassword, String newPassword) {
        if (loginUser == null || loginUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (StrUtil.hasBlank(oldPassword, newPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (newPassword.length() < 6) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "新密码不能少于 6 位");
        }
        if (oldPassword.equals(newPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "新密码不能与原密码相同");
        }
        User user = this.getById(loginUser.getId());
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        if (!isPasswordMatch(oldPassword, user.getUserPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "原密码错误");
        }
        User update = new User();
        update.setId(user.getId());
        update.setUserPassword(getEncryptPassword(newPassword));
        return this.updateById(update);
    }

    @Override
    public boolean resetPassword(Long userId, String newPassword) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
        }
        if (StrUtil.isBlank(newPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "新密码不能为空");
        }
        if (newPassword.length() < 6 || newPassword.length() > 72) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "新密码长度需在 6 到 72 个字符之间");
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        User update = new User();
        update.setId(userId);
        update.setUserPassword(getEncryptPassword(newPassword));
        boolean result = this.updateById(update);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "重置密码失败");
        }
        return true;
    }

    @Override
    public boolean updateProfile(User loginUser, UserUpdateProfileRequest request) {
        if (loginUser == null || loginUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 只允许修改当前登录用户的资料字段，不能改角色、账号和密码
        User update = new User();
        update.setId(loginUser.getId());
        update.setUserName(request.getUserName());
        update.setUserAvatar(request.getUserAvatar());
        update.setUserProfile(request.getUserProfile());
        boolean result = this.updateById(update);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新个人资料失败");
        }
        return true;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user,userVO);
        return userVO;
    }

    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if(CollUtil.isEmpty(userList)){
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String userAccount = userQueryRequest.getUserAccount();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ObjUtil.isNotNull(id), "id", id);
        queryWrapper.eq(StrUtil.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.like(StrUtil.isNotBlank(userAccount), "userAccount", userAccount);
        queryWrapper.like(StrUtil.isNotBlank(userName), "userName", userName);
        queryWrapper.like(StrUtil.isNotBlank(userProfile), "userProfile", userProfile);
        // 排序：sortField 会被拼进 ORDER BY，只接受白名单内的字段
        if (StrUtil.isNotEmpty(sortField)) {
            if (!SORT_FIELD_WHITELIST.contains(sortField)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,"排序字段不合法");
            }
            queryWrapper.orderBy(true, "ascend".equals(sortOrder), sortField);
        }

        return queryWrapper;
    }

    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }



}




