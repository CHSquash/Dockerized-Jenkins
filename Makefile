up:
	docker-compose -f docker-compose.yml up -d

down:
	docker-compose -f docker-compose.yml stop
	docker-compose -f docker-compose.yml rm -f

build:
	docker build -t jenkins -f Dockerfile .

jenkins-plugins:
	bash ./plugins.sh plugins.txt

clean:
	docker rm -f $$(docker ps -a -q) || true
	docker rmi -f $$(docker images -q) || true
	docker volume rm $$(docker volume ls -q) || true
