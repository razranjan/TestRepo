package com.bigbasket;

import io.vertx.core.*;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

public class OrderService extends AbstractVerticle {

    private JDBCPool dbClient;
    private WebClient webClient;

    @Override
    public void start(Promise<Void> startPromise) {
        // MySQL Configuration
        JsonObject dbConfig = new JsonObject()
                .put("url", "jdbc:mysql://mysql.default.svc.cluster.local:3306/bb_db?useSSL=false&allowPublicKeyRetrieval=true")
//                .put("url", "jdbc:mysql://localhost:3306/orders_db")
                .put("driver_class", "com.mysql.cj.jdbc.Driver")
                .put("user", "root")
                .put("password", "password")
                .put("max_pool_size", 5);

        dbClient = JDBCPool.pool(vertx, dbConfig);
        webClient = WebClient.create(vertx);

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.post("/order").handler(this::handleOrder);

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(8081, result -> {
                    if (result.succeeded()) {
                        System.out.println("Order Service Running on Port 8081");
                        startPromise.complete();
                    } else {
                        startPromise.fail(result.cause());
                    }
                });
    }

//    private void handleOrder(io.vertx.ext.web.RoutingContext ctx) {
//        JsonObject orderRequest = ctx.getBodyAsJson();
//        if (orderRequest == null) {
//            ctx.response().setStatusCode(400).end("Invalid request: JSON required");
//            return;
//        }
//
//        // Read fields from the request
//        Integer productId = orderRequest.getInteger("product_id");
//        String productName = orderRequest.getString("product_name");
//        Integer quantity = orderRequest.getInteger("quantity");
//
//        if (productId == null || productName == null || quantity == null) {
//            ctx.response().setStatusCode(400)
//                    .end("Invalid request: 'product_id', 'product_name', and 'quantity' are required");
//            return;
//        }
//
//        // Step 1: Insert a new order with the provided product info, status "CREATED", and is_payment_done as false.
//        dbClient.preparedQuery(
//                        "INSERT INTO order_info (product_id, product_name, quantity, order_status, is_payment_done) VALUES (?, ?, ?, ?, ?)")
//                .execute(Tuple.of(productId, productName, quantity, "CREATED", false))
//                .compose(insertResult ->
//                        // Step 2: Retrieve the auto-generated order_id.
//                        dbClient.preparedQuery("SELECT LAST_INSERT_ID() as order_id").execute()
//                )
//                .compose(rows -> {
//                    // Expect one row with the generated order_id.
//                    Row row = rows.iterator().next();
//                    int orderId = row.getInteger("order_id");
//
//                    // Prepare calls to Inventory and Payment services.
//                    // For inventory, pass the product_id from the request (not the order_id) and the quantity.
//                    // changing host from "localhost" to "inventory-service.default.svc.cluster.local"
//                    // changing below port from 8082 to 80
//                    Future<Void> inventoryFuture = webClient.post(80, "inventory-service.default.svc.cluster.local", "/inventory")
//                            .timeout(4000) // Set timeout to 4 seconds
//                            .sendJsonObject(new JsonObject()
//                                    .put("product_id", productId)
//                                    .put("quantity", quantity))
//                            .mapEmpty();
//
//                    // Payment service call sends the orderId to process the payment.
//                    // changing host from "localhost" to "payment-service.default.svc.cluster.local"
//                    // changing below port from 8083 to 80
//                    Future<Void> paymentFuture = webClient.post(80, "payment-service.default.svc.cluster.local", "/payment")
//                            .timeout(3000) // Set timeout to 3 seconds
//                            .sendJsonObject(new JsonObject().put("orderId", orderId))
//                            .mapEmpty();
//
//                    // Wait for both external calls to complete.
//                    return CompositeFuture.all(inventoryFuture, paymentFuture)
//                            .map(orderId);
//                })
//                .compose(orderId ->
//                        // Step 3: Update the order_info record to mark order as COMPLETED and is_payment_done as true.
//                        dbClient.preparedQuery(
//                                        "UPDATE order_info SET order_status = ?, is_payment_done = ? WHERE order_id = ?")
//                                .execute(Tuple.of("COMPLETED", true, orderId))
//                )
//                .onSuccess(updateResult -> {
//                    ctx.response().end("Order processed successfully");
//                })
//                .onFailure(err -> {
//                    ctx.response().setStatusCode(500)
//                            .end("Order processing failed: " + err.getMessage());
//                });
//    }

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

        // Step 1: Insert a new order with "CREATED" status
        dbClient.preparedQuery(
                        "INSERT INTO order_info (product_id, product_name, quantity, order_status, is_payment_done) VALUES (?, ?, ?, ?, ?)")
                .execute(Tuple.of(productId, productName, quantity, "CREATED", false))
                .compose(insertResult ->
                        dbClient.preparedQuery("SELECT LAST_INSERT_ID() as order_id").execute()
                )
                .compose(rows -> {
                    Row row = rows.iterator().next();
                    int orderId = row.getInteger("order_id");

                    // Step 2: Call InventoryService first
                    Future<Void> inventoryFuture = webClient.post(80, "inventory-service.default.svc.cluster.local", "/inventory")
                            .timeout(10000)
                            .sendJsonObject(new JsonObject()
                                    .put("product_id", productId)
                                    .put("quantity", quantity))
                            .compose(response -> {
                                if (response.statusCode() >= 400) {
                                    return Future.failedFuture("Inventory failed with status " + response.statusCode());
                                }
                                return Future.succeededFuture();
                            });

                    // Step 3: Call PaymentService only if InventoryService succeeds
                    Future<Void> paymentFuture = inventoryFuture.compose(v ->
                            webClient.post(80, "payment-service.default.svc.cluster.local", "/payment")
                                    .timeout(3000)
                                    .sendJsonObject(new JsonObject().put("orderId", orderId))
                                    .compose(response -> {
                                        if (response.statusCode() >= 400) {
                                            return Future.failedFuture("Payment failed with status " + response.statusCode());
                                        }
                                        return Future.succeededFuture();
                                    })
                    );

                    return paymentFuture
                            .map(orderId) // If both services succeed, return orderId
                            .recover(err -> {
                                // Step 4: If any service fails, update order as "FAILED"
                                return dbClient.preparedQuery("UPDATE order_info SET order_status = ? WHERE order_id = ?")
                                        .execute(Tuple.of("FAILED", orderId))
                                        .compose(res -> Future.failedFuture(err));
                            });
                })
                .compose(orderId ->
                        // Step 5: If everything is successful, mark order as "COMPLETED"
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

}