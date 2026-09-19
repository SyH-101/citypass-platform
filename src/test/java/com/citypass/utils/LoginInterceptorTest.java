package com.citypass.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class LoginInterceptorTest {

    private final LoginInterceptor interceptor = new LoginInterceptor();

    @AfterEach
    void cleanThreadLocal() {
        UserHolder.removeUser();
    }

    @Test
    void anonymousVenueReadIsPublicButWriteRequiresLogin() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/venues/1");
        assertTrue(interceptor.preHandle(get, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest put = new MockHttpServletRequest("PUT", "/venues");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(put, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void publicVenuePolicyMatchesOnlyDeclaredReadRoutes() throws Exception {
        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/venues/of/type"),
                new MockHttpServletResponse(), new Object()));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/venues/private-probe"),
                response, new Object()));
        assertEquals(401, response.getStatus());
    }
}
