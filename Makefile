.PHONY: build test run docker-build docker-run clean help

# Default target
help:
	@echo "TrustMesh Makefile"
	@echo ""
	@echo "Available commands:"
	@echo "  make build        - Compile and package the application (skips tests)"
	@echo "  make test         - Run the unit and integration tests"
	@echo "  make run          - Run the Spring Boot application locally"
	@echo "  make docker-build - Build the Docker image"
	@echo "  make docker-up    - Run the application via docker-compose"
	@echo "  make docker-down  - Stop the docker-compose stack"
	@echo "  make clean        - Clean the target directory"

build:
	./mvnw clean package -DskipTests

test:
	./mvnw test

run:
	./mvnw spring-boot:run

docker-build:
	docker build -t trustmesh-app:latest .

docker-up:
	docker-compose up -d

docker-down:
	docker-compose down

clean:
	./mvnw clean
