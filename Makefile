.PHONY: test frontend package run

test:
	mvn -q test

frontend:
	cd frontend && npm ci && npm run build
	rm -rf src/main/resources/static
	mkdir -p src/main/resources/static
	cp -r frontend/dist/* src/main/resources/static/

package: frontend
	mvn -q -DskipTests package

run:
	java -jar target/mergeguard-0.1.0.jar
