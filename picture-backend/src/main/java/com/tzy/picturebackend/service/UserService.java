package com.tzy.picturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tzy.picturebackend.model.dto.user.UserQueryRequest;
import com.tzy.picturebackend.model.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tzy.picturebackend.model.vo.LoginUserVO;
import com.tzy.picturebackend.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
* @author shifang37
* @description 针对表【user(用户)】的数据库操作Service
* @createDate 2026-03-05 21:32:07
*/
public interface UserService extends IService<User> {
    long userRegister(String userAccount, String userPassword, String checkPassword);

    String getEncryptPassword(String userPassword);

    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    LoginUserVO getLoginUserVO(User user);

    User getLoginUser(HttpServletRequest request);

    /**
     * 获取当前登录用户，未登录时返回 null（不抛异常）
     *
     * @param request
     * @return
     */
    User getLoginUserOrNull(HttpServletRequest request);

    boolean userLogout(HttpServletRequest request);

    /**
     * 修改当前登录用户的密码
     *
     * @param loginUser   当前登录用户
     * @param oldPassword 原密码
     * @param newPassword 新密码
     * @return 是否成功
     */
    boolean updatePassword(User loginUser, String oldPassword, String newPassword);

    UserVO getUserVO(User user);

    List<UserVO> getUserVOList(List<User> userList);

    QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest);

    /**
     * 是否为管理员
     *
     * @param user
     * @return
     */
    boolean isAdmin(User user);

}
