package com.company.tradejournal.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.tradejournal.entity.SyncFileEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 自动同步处理记录读写。
 */
@Mapper
public interface SyncFileMapper extends BaseMapper<SyncFileEntity> {

    /**
     * 按内容指纹查是否处理过。
     *
     * @param fingerprint SHA-256
     * @return 已存在时返回记录，否则 null
     */
    @Select("""
            SELECT id, fingerprint, file_name, file_path, import_type, account_id,
                   total, success, skipped, failed, status, message, scanned_at, imported_at
            FROM t_sync_file
            WHERE fingerprint = #{fingerprint}
            LIMIT 1
            """)
    SyncFileEntity selectByFingerprint(@Param("fingerprint") String fingerprint);

    /**
     * 最近处理过的文件，按时间倒序。
     *
     * @param limit 条数
     * @return 记录列表
     */
    @Select("""
            SELECT id, fingerprint, file_name, file_path, import_type, account_id,
                   total, success, skipped, failed, status, message, scanned_at, imported_at
            FROM t_sync_file
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<SyncFileEntity> selectRecent(@Param("limit") int limit);
}
