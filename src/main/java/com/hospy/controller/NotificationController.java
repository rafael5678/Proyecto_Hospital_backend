package com.hospy.controller;

import com.hospy.dto.NotificationResponse;
import com.hospy.service.NotificationInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationInboxService inbox;

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> list() {
        return ResponseEntity.ok(inbox.list(currentEmail()));
    }

    @GetMapping("/unread")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        return ResponseEntity.ok(Map.of("total", inbox.unreadCount(currentEmail())));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markOneRead(@PathVariable Long id) {
        inbox.markRead(id, currentEmail());
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        inbox.markAllRead(currentEmail());
        return ResponseEntity.ok().build();
    }

    private String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
