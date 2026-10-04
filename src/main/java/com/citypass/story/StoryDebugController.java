package com.citypass.story;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Development-only UI, no admin endpoints or credentials embedded in HTML. */
@RestController
@ConditionalOnProperty(name="story-files.debug-page-enabled",havingValue="true")
public class StoryDebugController {
    @GetMapping("/debug/story-files")
    public ResponseEntity<Resource> page() { return resource("html",MediaType.TEXT_HTML); }
    @GetMapping("/debug/story-files.js")
    public ResponseEntity<Resource> script() { return resource("js",MediaType.valueOf("application/javascript")); }
    @GetMapping("/debug/story-files.css")
    public ResponseEntity<Resource> style() { return resource("css",MediaType.valueOf("text/css")); }
    private ResponseEntity<Resource> resource(String extension,MediaType type) {
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").body(new ClassPathResource("debug/story-files."+extension));
    }
}
