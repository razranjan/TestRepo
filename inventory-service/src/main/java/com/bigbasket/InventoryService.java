package com.bigbasket;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

public class InventoryService extends AbstractVerticle {

    private JDBCPool dbClient;
    private WebClient webClient;

    @Override
    public void start(Promise<Void> startPromise) {
        // MySQL Configuration
        JsonObject dbConfig = new JsonObject()
                .put("url", "jdbc:mysql://mysql.default.svc.cluster.local:3306/bb_db?useSSL=false&allowPublicKeyRetrieval=true")
//                .put("url", "jdbc:mysql://localhost:3306/inventory_db")
                .put("driver_class", "com.mysql.cj.jdbc.Driver")
                .put("user", "root")
                .put("password", "password")
                .put("max_pool_size", 5);

        dbClient = JDBCPool.pool(vertx, dbConfig);
        webClient = WebClient.create(vertx);

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.post("/inventory").handler(this::updateInventory);

        // New GET API to fetch inventory details
        router.get("/inventory/:productId").handler(this::getInventoryDetails);

        vertx.createHttpServer().requestHandler(router).listen(8082, http -> {
            if (http.succeeded()) {
                startPromise.complete();
                System.out.println("Inventory Service Running on Port 8082");
            } else {
                startPromise.fail(http.cause());
            }
        });
    }
//
//    private void updateInventory(RoutingContext ctx) {
//        JsonObject request = ctx.getBodyAsJson();
//        String item = request.getString("item");
//        int quantity = request.getInteger("quantity");
//
//        dbClient.preparedQuery("UPDATE inventory SET count = count - ? WHERE item = ?")
//                .execute(Tuple.of(quantity, item))
//                .onSuccess(rows -> ctx.response().end("Inventory updated for " + item))
//                .onFailure(err -> ctx.response().setStatusCode(500).end("Inventory update failed: " + err.getMessage()));
//    }

    // Fetch Inventory Details
    private void getInventoryDetails(RoutingContext context) {
        String itemId = context.pathParam("productId");
        if (itemId == null || itemId.isEmpty()) {
            context.response().setStatusCode(400).end("Invalid product ID");
            return;
        }

        String query = "SELECT * FROM inventory WHERE product_Id = ?";
        dbClient.preparedQuery(query)
                .execute(Tuple.of(itemId))
                .onSuccess(result -> {
                    if (result.rowCount() == 0) {
                        context.response().setStatusCode(404).end("product not found");
                    } else {
                        RowSet<Row> rows = result;
                        JsonArray inventoryData = new JsonArray();
                        for (Row row : rows) {
                            JsonObject item = new JsonObject()
                                    .put("productId", row.getInteger("product_id"))
                                    .put("productName", row.getString("product_name"))
                                    .put("quantity", row.getInteger("quantity"));
                            inventoryData.add(item);
                        }
                        context.response()
                                .putHeader("Content-Type", "application/json")
                                .end(inventoryData.encode());
                    }
                })
                .onFailure(err -> {
                    err.printStackTrace();
                    context.response().setStatusCode(500).end("Database error in fetching");
                });
    }

    public void updateInventory(RoutingContext ctx) {
        JsonObject request = ctx.getBodyAsJson();
        if (request == null) {
            ctx.response().setStatusCode(400).end("Invalid request: Body is missing or not valid JSON");
            return;
        }

        Integer productId = request.getInteger("product_id");
        Integer quantityToDeduct = request.getInteger("quantity");
        if (productId == null || quantityToDeduct == null) {
            ctx.response().setStatusCode(400).end("Invalid request: 'product_id' and 'quantity' are required");
            return;
        }

        // Step 1: Fetch the row for product_id
        dbClient.preparedQuery("SELECT * FROM inventory WHERE product_id = ?")
                .execute(Tuple.of(productId))
                .onSuccess(rows -> {
                    if (rows.rowCount() == 0) {
                        ctx.response().setStatusCode(404).end("Product not found");
                        return;
                    }
                    // Assume only one row per product_id
                    Row row = rows.iterator().next();
                    Integer currentQuantity = row.getInteger("quantity");
                    Integer currentVersion = row.getInteger("version");

                    //Check if enough quantity is available
                    if (currentQuantity < quantityToDeduct) {
                        System.out.println("Insufficient inventory. Current available: " + currentQuantity);
                        ctx.response().setStatusCode(409).end("Insufficient inventory. Current available: " + currentQuantity);
                        return;
                    }

                    // Step 2: Attempt to update with optimistic locking
                    dbClient.preparedQuery("UPDATE inventory SET quantity = quantity - ?, version = version + 1 WHERE product_id = ? AND version = ?")
                            .execute(Tuple.of(quantityToDeduct, productId, currentVersion))
                            .onSuccess(updateResult -> {
                                if (updateResult.rowCount() == 0) {
                                    // No rows updated means the version did not match - optimistic locking conflict.
                                    System.out.println("Inventory update conflict for product_id " + productId);
                                    ctx.response().setStatusCode(409).end("Inventory update conflict for product_id " + productId);
                                } else {
                                    System.out.println("Inventory updated for product_id " + productId);
                                    ctx.response().end("Inventory updated for product_id " + productId);
                                }
                            })
                            .onFailure(err -> {
                                System.out.println("Inventory update failed: " + err.getMessage());
                                ctx.response().setStatusCode(500).end("Inventory update failed: " + err.getMessage());
                            });
                })
                .onFailure(err -> {
                    System.out.println("Failed to fetch product: " + err.getMessage());
                    ctx.response().setStatusCode(500).end("Failed to fetch product: " + err.getMessage());
                });
    }
}