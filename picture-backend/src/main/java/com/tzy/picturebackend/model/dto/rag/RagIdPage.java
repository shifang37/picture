package com.tzy.picturebackend.model.dto.rag;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 混合 / 语义检索结果缓存值。
 * <p>
 * 只缓存 id 与分页信息：图片 VO 包含作者资料等按请求者变化的内容，缓存整页 VO 会串数据；
 * id 列表与请求者无关，回表时再按当前请求补全作者与审核可见性。
 * <p>
 * 与 {@code PictureListPage} 同理，不直接序列化 MyBatis-Plus 的 {@link Page}：
 * 它带一批只读属性，JSON 反序列化容易出问题。
 */
@Data
public class RagIdPage implements Serializable {

    private List<Long> ids;

    private long total;

    private long current;

    private long size;

    public static RagIdPage of(Page<Long> page) {
        RagIdPage result = new RagIdPage();
        result.setIds(page.getRecords());
        result.setTotal(page.getTotal());
        result.setCurrent(page.getCurrent());
        result.setSize(page.getSize());
        return result;
    }

    public Page<Long> toPage() {
        Page<Long> page = new Page<>(current, size, total);
        page.setRecords(ids);
        return page;
    }

    private static final long serialVersionUID = 1L;
}
