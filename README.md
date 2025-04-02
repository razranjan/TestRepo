# TestRepo
Order Service , Inventory service and Payment Service Demo
For each service
./gradlew clean shadowJar
java -jar build/libs/inventory-service-all.jar
eval $(minikube docker-env)
docker build -t order-service ./order-service
docker build -t inventory-service ./inventory-service
docker build -t payment-service ./payment-service
docker build -t retry-order-service ./retry-order-service
kubectl apply -f k8s/k8s-deployment.yaml
kubectl rollout restart deployment/inventory-service
kubectl rollout restart deployment/order-service
kubectl rollout restart deployment/payment-service
kubectl rollout restart deployment/retry-order-service
kubectl apply -f mysql-deployment.yaml
kubectl rollout restart deployment/mysql
minikube dashboard
kubectl port-forward svc/retry-order-service 8085:80
kubectl port-forward svc/order-service 8081:80
kubectl port-forward svc/inventory-service 8082:80
kubectl port-forward svc/payment-service 8083:80
kubectl port-forward svc/mysql 3306:3306
kubectl port-forward -n chaos-mesh svc/chaos-dashboard 2333:2333