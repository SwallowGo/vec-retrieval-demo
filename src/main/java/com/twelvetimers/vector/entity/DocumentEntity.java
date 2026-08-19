package com.twelvetimers.vector.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 文档表：原始文本、渠道、状态、向量（256 维 float 编码为 1024 字节）、溯源元信息。
 */
@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务文档 ID，唯一 */
    @Column(name = "doc_id", nullable = false, unique = true, length = 64)
    private String docId;

    /** 原始文本（CLOB） */
    @Lob
    @Column(name = "text", nullable = false)
    private String text;

    /** 渠道标识，默认 default */
    @Column(name = "channel", nullable = false, length = 32)
    private String channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DocumentStatus status;

    /** 向量字节（VARBINARY，256 × 4 字节）；就绪前为 null */
    @Column(name = "vector", columnDefinition = "VARBINARY(2048)")
    private byte[] vector;

    /** 被检索命中次数（检索时原子累加） */
    @Column(name = "hit_count", nullable = false)
    private long hitCount = 0L;

    @Column(name = "submit_time", nullable = false)
    private LocalDateTime submitTime;

    @Column(name = "complete_time")
    private LocalDateTime completeTime;

    @Column(name = "invalid_time")
    private LocalDateTime invalidTime;
}
