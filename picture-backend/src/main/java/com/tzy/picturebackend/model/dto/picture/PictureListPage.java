package com.tzy.picturebackend.model.dto.picture;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.model.vo.PictureVO;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 公开图片列表的缓存值。
 * <p>
 * 只保留 records / total / current / size：MyBatis-Plus 的 {@link Page} 带一批只读属性，
 * 直接 JSON 反序列化容易出问题，所以用这个简单对象做缓存载体。
 */
@Data
public class PictureListPage implements Serializable {

    private List<PictureVO> records;

    private long total;

    private long current;

    private long size;

    public static PictureListPage of(Page<PictureVO> page) {
        PictureListPage result = new PictureListPage();
        result.setRecords(page.getRecords());
        result.setTotal(page.getTotal());
        result.setCurrent(page.getCurrent());
        result.setSize(page.getSize());
        return result;
    }

    public Page<PictureVO> toPage() {
        Page<PictureVO> page = new Page<>(current, size, total);
        page.setRecords(records);
        return page;
    }

    private static final long serialVersionUID = 1L;
}
