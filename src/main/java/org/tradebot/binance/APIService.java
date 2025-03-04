package org.tradebot.binance;

import org.json.JSONObject;
import org.tradebot.domain.*;
import org.tradebot.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static org.tradebot.util.JsonParser.*;

//TODO 429 error
public class APIService {

    private final Log log;
    private final HttpClient httpClient;

    public APIService(HttpClient httpClient,
                      int clientNumber) {
        this.httpClient = httpClient;
        this.log = new Log(clientNumber);
    }

    public HTTPResponse<Order> placeOrder(Order order) {
        Map<String, String> params = new HashMap<>();
        params.put("newClientOrderId", String.valueOf(order.getNewClientOrderId()));
        return order(order, params, "POST");
    }

    public HTTPResponse<Order> modifyOrder(Order order) {
        Map<String, String> params = new HashMap<>();
        params.put("origClientOrderId", String.valueOf(order.getNewClientOrderId()));
        return order(order, params, "PUT");
    }

    private HTTPResponse<Order> order(Order order, Map<String, String> params, String method) {
        validateOrder(order);

        params.put("symbol", order.getSymbol());
        params.put("side", order.getSide().toString());
        params.put("type", order.getType().toString());

        if (order.getPrice() != null) {
            params.put("price", String.valueOf(order.getPrice()));
        }
        if (order.getQuantity() != null) {
            params.put("quantity", String.valueOf(order.getQuantity()));
        }
        if (order.getStopPrice() != null) {
            params.put("stopPrice", String.valueOf(order.getStopPrice()));
        }
        if (order.isReduceOnly() != null) {
            params.put("reduceOnly", String.valueOf(order.isReduceOnly()));
        }
        if (order.isClosePosition() != null) {
            params.put("closePosition", String.valueOf(order.isClosePosition()));
        }
        if (order.getTimeInForce() != null) {
            params.put("timeInForce", String.valueOf(order.getTimeInForce()));
        }

        HTTPResponse<String> response = httpClient.sendRequest("/fapi/v1/order", method, params, true);

        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseOrder(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    private void validateOrder(Order order) {
        if (order.getSymbol() == null) {
            throw log.throwError("empty symbol :: " + order);
        }
        if (order.getPrice() != null && order.getPrice().doubleValue() <= 0) {
            throw log.throwError("Invalid price :: " + order.getPrice());
        }
        if (order.getQuantity() != null && order.getQuantity().doubleValue() <= 0) {
            throw log.throwError("Invalid quantity :: " + order.getQuantity());
        }
        if (order.getStopPrice() != null && order.getStopPrice().doubleValue() <= 0) {
            throw log.throwError("Invalid stop price :: " + order.getStopPrice());
        }
        if (order.getNewClientOrderId() == null) {
            throw log.throwError("client id is null");
        }
        if (order.getType() == Order.Type.LIMIT && order.getTimeInForce() == null) {
            throw log.throwError("timeInForce required for LIMIT order");
        }
    }

    public HTTPResponse<String> cancelOrder(String symbol, String clientId) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("origClientOrderId", clientId);

        return httpClient.sendRequest("/fapi/v1/order", "DELETE", params);
    }

    public void cancelAllOpenOrders(String symbol) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        httpClient.sendRequest("/fapi/v1/allOpenOrders", "DELETE", params);
    }

    public HTTPResponse<Order> queryOrder(String symbol, String clientId) {
        Map<String, String> params = new HashMap<>();

        params.put("symbol", symbol);
        params.put("origClientOrderId", clientId);

        HTTPResponse<String> response = httpClient.sendRequest("/fapi/v1/order", "GET", params);
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseOrder(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public void setLeverage(String symbol, int leverage) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("leverage", String.valueOf(leverage));

        httpClient.sendRequest("/fapi/v1/leverage", "POST", params);
    }

    public HTTPResponse<Integer> getLeverage(String symbol) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);

        HTTPResponse<String> response = httpClient.sendRequest("/fapi/v2/positionRisk", "GET", params);
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseLeverage(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<Double> getAvailableBalance(String baseAsset) {
        HTTPResponse<String> response = httpClient.sendRequest("/fapi/v2/balance", "GET", new HashMap<>());
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseAvailableBalance(response.getValue(), baseAsset));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<Double> getBalance(String baseAsset) {
        HTTPResponse<String> response = httpClient.sendRequest("/fapi/v2/balance", "GET", new HashMap<>());
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseBalance(response.getValue(), baseAsset));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<AccountInfo> getAccountInfo() {
        HTTPResponse<String> response = httpClient.sendRequest("/fapi/v2/account", "GET", new HashMap<>());
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseAccountInfo(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<Position> getOpenPosition(String symbol) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);

        HTTPResponse<String> response = httpClient.sendRequest("/fapi/v3/positionRisk", "GET", params);
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parsePosition(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<String> getUserStreamKey() {
        HTTPResponse<String> response = httpClient.sendRequest("/fapi/v1/listenKey", "POST", new HashMap<>());
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), new JSONObject(response.getValue()).getString("listenKey"));
        } else {
            return response;
        }
    }

    public HTTPResponse<String> keepAliveUserStreamKey() {
        return httpClient.sendRequest("/fapi/v1/listenKey", "PUT", new HashMap<>());
    }

    public void removeUserStreamKey() {
        httpClient.sendRequest("/fapi/v1/listenKey", "DELETE", new HashMap<>());
    }

    public HTTPResponse<List<Order>> getOpenOrders(String symbol) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase());

        HTTPResponse<String> response = httpClient.sendRequest("/fapi/v1/openOrders", "GET", params);
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseOrders(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }
}
