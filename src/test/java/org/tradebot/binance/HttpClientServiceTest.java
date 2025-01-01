package org.tradebot.binance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;

import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpClientServiceTest {

    @InjectMocks
    private HttpClientService httpClientService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        httpClientService = spy(new HttpClientService());
    }

    @Test
    void testGenerateSignature() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", "BTCUSDT");
        params.put("side", "BUY");
        params.put("type", "LIMIT");

        String signature = httpClientService.generateSignature(params);

        assertNotNull(signature);
        assertNotNull(signature);
        assertFalse(signature.isEmpty());
    }

    @Test
    void testGetParamsString() {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", "BTCUSDT");
        params.put("side", "BUY");
        params.put("type", "LIMIT");

        String paramString = httpClientService.getParamsString(params);
        assertEquals("side=BUY&symbol=BTCUSDT&type=LIMIT", paramString);
    }

    @Test
    void testSendRequest_SuccessResponse() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", "BTCUSDT");
        params.put("side", "BUY");
        params.put("type", "LIMIT");

        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(200);

        doReturn("mocked-response").when(httpClientService)
                .sendRequest(anyString(), anyString(), anyMap());

        String response = httpClientService.sendRequest("/fapi/v1/order", "POST", params);

        assertEquals("mocked-response", response);
    }

    @Test
    void testSendRequest_ErrorResponse() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", "BTCUSDT");
        params.put("side", "BUY");
        params.put("type", "LIMIT");

        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(400);

        doThrow(new RuntimeException("mocked error")).when(httpClientService)
                .sendRequest(anyString(), anyString(), anyMap());

        Exception exception = assertThrows(RuntimeException.class, () ->
                httpClientService.sendRequest("/fapi/v1/order", "POST", params));

        assertEquals("mocked error", exception.getMessage());
    }
}
