package com.bigbasket;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Tuple;

public class PaymentService extends AbstractVerticle {
    private JDBCPool dbClient;

    @Override
    public void start(Promise<Void> startPromise) {
        JsonObject dbConfig = new JsonObject()
                .put("url", "jdbc:mysql://mysql.default.svc.cluster.local:3306/bb_db?useSSL=false&allowPublicKeyRetrieval=true")
//                .put("url", "jdbc:mysql://localhost:3306/payment_db")
                .put("driver_class", "com.mysql.cj.jdbc.Driver")
                .put("user", "root")
                .put("password", "password")
                .put("max_pool_size", 5);

        dbClient = JDBCPool.pool(vertx, dbConfig);
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.post("/payment").handler(this::processPayment);

        vertx.createHttpServer().requestHandler(router).listen(8083, http -> {
            if (http.succeeded()) {
                startPromise.complete();
                System.out.println("Payment Service Running on Port 8083");
            } else {
                startPromise.fail(http.cause());
            }
        });
    }

    private void processPayment(RoutingContext ctx) {
        JsonObject request = ctx.getBodyAsJson();
        String orderId = request.getString("orderId");
        String status = "COMPLETED";

        dbClient.preparedQuery("INSERT INTO payment_info (order_id, payment_status) VALUES (?, ?)")
                .execute(Tuple.of(orderId, status))
                .onSuccess(rows -> ctx.response().end("Payment processed for order " + orderId))
                .onFailure(err -> ctx.response().setStatusCode(500).end("Payment processing failed: " + err.getMessage()));
    }

}
