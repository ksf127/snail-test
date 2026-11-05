package com.company.tradejournal.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 自动同步处理记录。
 *
 * <p>【解决什么问题】定时任务每 2 小时扫一次下载目录，如果只靠文件名判断「导过没有」，
 * 券商重复导出同名文件会被反复入库。这里保存文件内容指纹（SHA-256），同一份内容只处理一次。
 *
 * <p>【为什么连失败也记】失败文件同样要占住指纹，否则每次扫描都会重试同一份坏文件、
 * 把日志刷满。文件被修改后指纹变化，会作为新文件重新尝试。
 */
@Data
@TableName("t_sync_file")
public class SyncFileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文件内容 SHA-256，唯一键；同内容重复导出只处理一次。 */
    private String fingerprint;

    private String fileName;

    private String filePath;

    /** 识别出的导入类型：trades / daily / positions，认不出时为空。 */
    private String importType;

    private Long accountId;

    private Integer total;

    private Integer success;

    private Integer skipped;

    private Integer failed;

    /** IMPORTED / SKIPPED / FAILED。 */
    private String status;

    private String message;

    private LocalDateTime scannedAt;

    private LocalDateTime importedAt;
}
