package com.bigbasket;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeoutException;

public class RetryOrderService extends AbstractVerticle {

    private JDBCPool dbClient;
    private WebClient webClient;

    @Override
    public void start(Promise<Void> startPromise) {
        // MySQL Configuration
        JsonObject dbConfig = new JsonObject()
                .put("url", "jdbc:mysql://mysql.default.svc.cluster.local:3306/bb_db?useSSL=false&allowPublicKeyRetrieval=true")
                .put("driver_class", "com.mysql.cj.jdbc.Driver")
                .put("user", "root")
                .put("password", "password")
                .put("max_pool_size", 5);

        dbClient = JDBCPool.pool(vertx, dbConfig);
        webClient = WebClient.create(vertx);

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.post("/retry-order").handler(this::handleOrder2);

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(8085, result -> {
                    if (result.succeeded()) {
                        System.out.println("Retry Order Service Running on Port 8085");
                        startPromise.complete();
                    } else {
                        startPromise.fail(result.cause());
                    }
                });
    }

    private void handleOrder2(io.vertx.ext.web.RoutingContext ctx) {
        JsonObject orderRequest = ctx.getBodyAsJson();
        if (orderRequest == null) {
            ctx.response().setStatusCode(400).end("Invalid request: JSON required");
            return;
        }

        // Read fields from the request
        Integer productId = orderRequest.getInteger("product_id");
        String productName = orderRequest.getString("product_name");
        Integer quantity = orderRequest.getInteger("quantity");

        if (productId == null || productName == null || quantity == null) {
            ctx.response().setStatusCode(400)
                    .end("Invalid request: 'product_id', 'product_name', and 'quantity' are required");
            return;
        }

        webClient.get(80, "inventory-service.default.svc.cluster.local", "/inventory/" + productId)
                .timeout(10000)
                .send()
                .onSuccess(response -> {
                    if (response.statusCode() == 200) {
                        System.out.println("Inventory Details: " + response.bodyAsString());
                        ctx.response().setStatusCode(200).end("Inventory Details: " + response.bodyAsString());

                    } else {
                        System.out.println("Failed to fetch inventory. Status: " + response.statusCode());
                        ctx.response().setStatusCode(500).end("Failed to fetch inventory. Status: " + response.statusCode());
                    }
                })
                .onFailure(err -> {
                    System.err.println("Error calling inventory service: " + err.getMessage());
                    ctx.response().setStatusCode(500).end("Error calling inventory service: " + err.getMessage());

                });



    }

    private void handleOrder(io.vertx.ext.web.RoutingContext ctx) {
        JsonObject orderRequest = ctx.getBodyAsJson();
        if (orderRequest == null) {
            ctx.response().setStatusCode(400).end("Invalid request: JSON required");
            return;
        }

        // Read fields from the request
        Integer productId = orderRequest.getInteger("product_id");
        String productName = orderRequest.getString("product_name");
        Integer quantity = orderRequest.getInteger("quantity");

        if (productId == null || productName == null || quantity == null) {
            ctx.response().setStatusCode(400)
                    .end("Invalid request: 'product_id', 'product_name', and 'quantity' are required");
            return;
        }

        // Step 1: Insert a new order with the provided product info, status "CREATED", and is_payment_done as false.
        dbClient.preparedQuery(
                        "INSERT INTO order_info (product_id, product_name, quantity, order_status, is_payment_done) VALUES (?, ?, ?, ?, ?)")
                .execute(Tuple.of(productId, productName, quantity, "CREATED", false))
                .compose(insertResult ->
                        // Step 2: Retrieve the auto-generated order_id.
                        dbClient.preparedQuery("SELECT LAST_INSERT_ID() as order_id").execute()
                )
                .compose(rows -> {
                    // Expect one row with the generated order_id.
                    Row row = rows.iterator().next();
                    int orderId = row.getInteger("order_id");

                    // Prepare calls to Inventory and Payment services.
                    // For inventory, pass the product_id from the request (not the order_id) and the quantity.

                    Future<Void> inventoryFuture = webClient.post(80, "inventory-service.default.svc.cluster.local", "/inventory")
                            .timeout(3000) // Set timeout to 3 seconds
                            .sendJsonObject(new JsonObject()
                                    .put("product_id", productId)
                                    .put("quantity", quantity))
                            .mapEmpty();

                    // Payment service call with retry mechanism
                    Future<Void> paymentFuture = retryPaymentService(orderId, 5); // Retry up to 5 times

                    // Wait for both external calls to complete.
                    return CompositeFuture.all(inventoryFuture, paymentFuture)
                            .map(orderId);
                })
                .compose(orderId ->
                        // Step 3: Update the order_info record to mark order as COMPLETED and is_payment_done as true.
                        dbClient.preparedQuery(
                                        "UPDATE order_info SET order_status = ?, is_payment_done = ? WHERE order_id = ?")
                                .execute(Tuple.of("COMPLETED", true, orderId))
                )
                .onSuccess(updateResult -> {
                    ctx.response().end("Order processed successfully");
                })
                .onFailure(err -> {
                    ctx.response().setStatusCode(500)
                            .end("Order processing failed: " + err.getMessage());
                });
    }

    private Future<Void> retryPaymentService(int orderId, int retriesLeft) {
        if (retriesLeft <= 0) {
            System.out.println("Payment Service Retry exhausted at " +formatDate());
            return Future.failedFuture("Payment service retries exhausted");
        }
        System.out.println("Payment Service called at " +formatDate() + "retry left :"+retriesLeft);

        return webClient.post(80, "payment-service.default.svc.cluster.local", "/payment")
                .timeout(5000) // Set timeout shorter than Chaos Mesh delay i.e 5 sec here and delay is 6 sec
                .sendJsonObject(new JsonObject().put("orderId", orderId))
                .mapEmpty()
                .compose(res -> Future.succeededFuture(), err -> {
                    if (err instanceof TimeoutException || (err instanceof HttpException && ((HttpException) err).getStatusCode() >= 500)) {
                        System.out.println("Retrying payment-service... attempts left: " + (retriesLeft - 1));
                        return retryPaymentService(orderId, retriesLeft - 1); // Retry on timeout or 5xx errors
                    } else {
                        return Future.failedFuture(err); // Fail immediately for client errors
                    }
                });
    }

    private String formatDate(){
        LocalDateTime myDateObj = LocalDateTime.now();
        DateTimeFormatter myFormatObj = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

        String formattedDate = myDateObj.format(myFormatObj);
        return formattedDate;
    }
}
