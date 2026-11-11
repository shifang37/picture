package com.tzy.picturebackend.manager;

/**
 * 排序字段不在搜索白名单内。
 * <p>
 * 这是客户端参数问题，不是 Elasticsearch 故障：调用方应回落到 MySQL 查询，
 * 由 MySQL 查询逻辑返回统一的 40000 参数错误，不需要打印异常堆栈。
 */
public class PictureSearchSortException extends IllegalArgumentException {

    public PictureSearchSortException(String message) {
        super(message);
    }
}
