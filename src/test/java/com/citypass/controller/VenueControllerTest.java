package com.citypass.controller;

import com.citypass.dto.Result;
import com.citypass.entity.Venue;
import com.citypass.config.WebExceptionAdvice;
import com.citypass.service.IVenueService;
import com.citypass.utils.CacheDegradedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VenueControllerTest {

    @Test
    void successfulDetailPublishesAuthoritativeGatewayVersion() {
        IVenueService service = mock(IVenueService.class);
        VenueController controller = new VenueController();
        controller.venueService = service;
        Venue venue = new Venue().setId(1L).setName("CityPass Venue").setCacheVersion(5L);
        when(service.queryById(1L)).thenReturn(Result.ok(venue));

        MockHttpServletResponse response = new MockHttpServletResponse();
        Result result = controller.queryVenueById(1L, response);

        assertEquals(Boolean.TRUE, result.getSuccess());
        assertEquals("5", response.getHeader("X-Venue-Cache-Version"));
        assertEquals("1", response.getHeader("X-Gateway-Cacheable"));
    }

    @Test
    void businessFailureCannotBeCachedByGateway() {
        IVenueService service = mock(IVenueService.class);
        VenueController controller = new VenueController();
        controller.venueService = service;
        when(service.queryById(404L)).thenReturn(Result.fail("场馆不存在！"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.queryVenueById(404L, response);

        assertNull(response.getHeader("X-Venue-Cache-Version"));
        assertNull(response.getHeader("X-Gateway-Cacheable"));
    }

    @Test
    void exhaustedCacheDegradationCapacityReturns503() throws Exception {
        IVenueService service = mock(IVenueService.class);
        VenueController controller = new VenueController();
        controller.venueService = service;
        when(service.queryById(9L)).thenThrow(new CacheDegradedException("缓存服务暂时不可用，请稍后重试"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionAdvice())
                .build();

        mvc.perform(get("/venues/9"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));
    }
}
