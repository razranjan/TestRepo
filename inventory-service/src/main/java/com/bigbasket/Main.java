package com.bigbasket;

import io.vertx.core.Vertx;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new InventoryService(), res -> {
            if (res.succeeded()) {
                System.out.println("Inventory Service deployed successfully!");
            } else {
                System.err.println("Failed to deploy Inventory Service: " + res.cause());
            }
        });
    }
}