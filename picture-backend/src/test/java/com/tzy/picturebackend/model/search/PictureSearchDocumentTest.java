package com.tzy.picturebackend.model.search;

import com.tzy.picturebackend.model.entity.Picture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片实体到搜索文档的映射：标签解析、空值与字段类型
 */
class PictureSearchDocumentTest {

    @Test
    @DisplayName("标签：去空白、去重，非法 JSON 按无标签处理")
    void from_normalizesTags() {
        Picture picture = picture(1L, "日落");
        picture.setTags("[\"海边\", \" 日落 \", \"海边\", \"\"]");

        PictureSearchDocument document = PictureSearchDocument.from(picture);

        assertEquals("1", document.getId());
        assertEquals(List.of("海边", "日落"), document.getTags());
    }

    @Test
    @DisplayName("标签：空串与非法 JSON 都不抛异常")
    void from_handlesInvalidTags() {
        Picture blank = picture(2L, "空白");
        blank.setTags("   ");
        Picture invalid = picture(3L, "非法");
        invalid.setTags("not-a-json-array");

        assertTrue(PictureSearchDocument.from(blank).getTags().isEmpty());
        assertTrue(PictureSearchDocument.from(invalid).getTags().isEmpty());
    }

    @Test
    @DisplayName("字段映射：id 转字符串、比例转 float、空字段保持 null")
    void from_mapsFields() {
        Picture picture = picture(9L, "海边");
        picture.setIntroduction("夕阳下的海面");
        picture.setCategory("风景");
        picture.setPicScale(1.7777);
        picture.setPicWidth(1920);
        picture.setPicHeight(1080);
        picture.setPicSize(1024L);
        picture.setReviewStatus(1);
        Date createTime = new Date();
        picture.setCreateTime(createTime);

        PictureSearchDocument document = PictureSearchDocument.from(picture);

        assertEquals("9", document.getId());
        assertEquals("海边", document.getName());
        assertEquals("风景", document.getCategory());
        assertEquals(1.7777f, document.getPicScale(), 0.0001f);
        assertEquals(1920, document.getPicWidth());
        assertEquals(1080, document.getPicHeight());
        assertEquals(1024L, document.getPicSize());
        assertEquals(createTime, document.getCreateTime());
        assertEquals(1, document.getReviewStatus());
        assertNull(document.getMachineReviewStatus());
    }

    private Picture picture(Long id, String name) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setName(name);
        return picture;
    }
}
