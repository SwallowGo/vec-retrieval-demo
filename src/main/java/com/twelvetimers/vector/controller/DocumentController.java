package com.twelvetimers.vector.controller;

import com.twelvetimers.vector.dto.DocumentDetailResponse;
import com.twelvetimers.vector.dto.DocumentListResponse;
import com.twelvetimers.vector.dto.SubmitDocumentRequest;
import com.twelvetimers.vector.dto.SubmitDocumentResponse;
import com.twelvetimers.vector.service.DocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档管理 API。
 */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Validated
public class DocumentController {

    private final DocumentService documentService;

    /** 文档入库：202 Accepted —— 向量化在后台异步执行，返回任务 ID */
    @PostMapping
    public ResponseEntity<SubmitDocumentResponse> submit(
            @Valid @RequestBody SubmitDocumentRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(documentService.submit(request));
    }

    /** 标记文档失效 */
    @PostMapping("/{docId}/invalidate")
    public ResponseEntity<Void> invalidate(
            @PathVariable @NotBlank(message = "docId 不能为空") String docId) {
        documentService.invalidate(docId);
        return ResponseEntity.noContent().build();
    }

    /** 文档列表：分页 + 渠道/状态过滤 */
    @GetMapping
    public DocumentListResponse list(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     @RequestParam(required = false) String channel,
                                     @RequestParam(required = false) String status) {
        return documentService.list(page, size, channel, status);
    }

    /** 文档详情：元信息 + 关联向量化任务 */
    @GetMapping("/{docId}")
    public DocumentDetailResponse detail(
            @PathVariable @NotBlank(message = "docId 不能为空") String docId) {
        return documentService.detail(docId);
    }
}
