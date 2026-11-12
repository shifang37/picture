package com.tzy.picturebackend.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.common.DeleteRequest;
import com.tzy.picturebackend.common.ResultUtils;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.model.dto.album.AlbumAddRequest;
import com.tzy.picturebackend.model.dto.album.AlbumPageRequest;
import com.tzy.picturebackend.model.dto.album.AlbumPictureRequest;
import com.tzy.picturebackend.model.dto.album.AlbumUpdateRequest;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.AlbumDetailVO;
import com.tzy.picturebackend.model.vo.AlbumVO;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.tzy.picturebackend.service.AlbumService;
import com.tzy.picturebackend.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图片专辑接口。
 * <p>
 * 读接口匿名可访问（私密专辑仅作者/管理员可见），写接口在 service 内校验归属，
 * 不依赖前端隐藏按钮。
 */
@RestController
@RequestMapping("album")
public class AlbumController {

    @Resource
    private AlbumService albumService;

    @Resource
    private UserService userService;

    /**
     * 创建专辑（默认私密）
     */
    @PostMapping("/add")
    public BaseResponse<AlbumVO> addAlbum(@Valid @RequestBody AlbumAddRequest albumAddRequest,
                                          HttpServletRequest request) {
        ThrowUtils.throwIf(albumAddRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(albumService.add(albumAddRequest, loginUser));
    }

    /**
     * 更新专辑（仅作者或管理员）
     */
    @PostMapping("/update")
    public BaseResponse<AlbumVO> updateAlbum(@Valid @RequestBody AlbumUpdateRequest albumUpdateRequest,
                                             HttpServletRequest request) {
        ThrowUtils.throwIf(albumUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(albumService.update(albumUpdateRequest, loginUser));
    }

    /**
     * 删除专辑（仅作者或管理员）：只删除专辑与关系，不影响图片本身
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteAlbum(@Valid @RequestBody DeleteRequest deleteRequest,
                                             HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null
                || deleteRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(albumService.delete(deleteRequest.getId(), loginUser));
    }

    /**
     * 获取专辑详情：私密专辑仅作者或管理员可见，其他人返回 40400
     */
    @GetMapping("/get")
    public BaseResponse<AlbumDetailVO> getAlbum(@RequestParam Long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "专辑 id 不能为空");
        return ResultUtils.success(albumService.get(id, request));
    }

    /**
     * 分页获取当前用户的专辑（含私密）
     */
    @PostMapping("/my/page")
    public BaseResponse<Page<AlbumVO>> listMyAlbumByPage(
            @Valid @RequestBody AlbumPageRequest albumPageRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(albumPageRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(albumService.pageMy(albumPageRequest, loginUser));
    }

    /**
     * 分页获取公开专辑；userId 为空时返回全站公开专辑
     */
    @GetMapping("/public/page")
    public BaseResponse<Page<AlbumVO>> listPublicAlbumByPage(
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long pageSize) {
        return ResultUtils.success(albumService.pagePublic(userId, current, pageSize));
    }

    /**
     * 批量加入图片（仅作者或管理员，单次最多 100 张，重复自动跳过）
     */
    @PostMapping("/picture/add")
    public BaseResponse<AlbumVO> addAlbumPictures(
            @Valid @RequestBody AlbumPictureRequest albumPictureRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(albumPictureRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(albumService.addPictures(albumPictureRequest, loginUser));
    }

    /**
     * 批量移除图片（仅作者或管理员，不删除图片本身）
     */
    @PostMapping("/picture/remove")
    public BaseResponse<AlbumVO> removeAlbumPictures(
            @Valid @RequestBody AlbumPictureRequest albumPictureRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(albumPictureRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(albumService.removePictures(albumPictureRequest, loginUser));
    }

    /**
     * 分页获取专辑内的图片：公开访问只返回已过审图片，作者/管理员可看到全部
     */
    @GetMapping("/picture/page")
    public BaseResponse<Page<PictureVO>> listAlbumPictureByPage(
            @RequestParam Long albumId,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long pageSize,
            HttpServletRequest request) {
        ThrowUtils.throwIf(albumId == null || albumId <= 0, ErrorCode.PARAMS_ERROR, "专辑 id 不能为空");
        return ResultUtils.success(albumService.pagePictures(albumId, current, pageSize, request));
    }

    /**
     * 重算专辑图片数量（管理员）：用于修复冗余计数漂移
     */
    @PostMapping("/recount")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> recountAlbum(@RequestParam(required = false) Long albumId) {
        return ResultUtils.success(albumService.recount(albumId));
    }
}
