package com.tzy.picturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tzy.picturebackend.model.dto.user.UserQueryRequest;
import com.tzy.picturebackend.model.dto.user.UserUpdateProfileRequest;
import com.tzy.picturebackend.model.entity.User;
import com.baomidou.mybatisplus.spring.service.IService;
import com.tzy.picturebackend.model.vo.LoginUserVO;
import com.tzy.picturebackend.model.vo.PublicUserVO;
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

    /**
     * 管理员重置指定用户的密码（不需要原密码）
     *
     * @param userId      目标用户 id
     * @param newPassword 新密码
     * @return 是否成功
     */
    boolean resetPassword(Long userId, String newPassword);

    /**
     * 修改当前登录用户的个人资料（昵称、头像、简介）
     *
     * @param loginUser 当前登录用户
     * @param request   个人资料请求
     * @return 是否成功
     */
    boolean updateProfile(User loginUser, UserUpdateProfileRequest request);

    /**
     * 管理端用户封装：包含账号、角色等内部字段，只能用于管理员接口
     *
     * @param user 用户实体
     * @return 管理端用户信息
     */
    UserVO getUserVO(User user);

    /**
     * 管理端用户列表封装：包含账号、角色等内部字段，只能用于管理员接口
     *
     * @param userList 用户实体列表
     * @return 管理端用户信息列表
     */
    List<UserVO> getUserVOList(List<User> userList);

    /**
     * 公开用户封装：只返回 id、昵称、头像、简介，供公开图片接口使用
     *
     * @param user 用户实体
     * @return 公开用户信息
     */
    PublicUserVO getPublicUserVO(User user);

    /**
     * 公开用户列表封装
     *
     * @param userList 用户实体列表
     * @return 公开用户信息列表
     */
    List<PublicUserVO> getPublicUserVOList(List<User> userList);

    QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest);

    /**
     * 是否为管理员
     *
     * @param user
     * @return
     */
    boolean isAdmin(User user);

}
