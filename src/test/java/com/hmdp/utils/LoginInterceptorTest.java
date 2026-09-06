package com.hmdp.utils;

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
    void anonymousShopReadIsPublicButWriteRequiresLogin() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/shop/1");
        assertTrue(interceptor.preHandle(get, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest put = new MockHttpServletRequest("PUT", "/shop");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(put, response, new Object()));
        assertEquals(401, response.getStatus());
    }
}
