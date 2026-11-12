package com.tzy.picturebackend.model.dto.album;

import com.tzy.picturebackend.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 「我的专辑」分页请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AlbumPageRequest extends PageRequest implements Serializable {

    private static final long serialVersionUID = 1L;
}
