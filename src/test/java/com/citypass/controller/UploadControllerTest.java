package com.citypass.controller;

import com.citypass.story.StoryProblem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadControllerTest {

    @Test
    void legacyFilenameWritesAreExplicitlyRetired() {
        StoryProblem problem =
                assertThrows(StoryProblem.class, () -> new UploadController().retired());
        assertEquals(410, problem.getStatus());
    }
}
