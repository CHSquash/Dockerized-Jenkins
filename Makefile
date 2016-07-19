build:
	docker build -t chungho/jenkins -f Dockerfile .

jenkins-plugins:
	bash ./plugins.sh plugins.txt

clean:
	docker rm -f $$(docker ps -a -q) || true
	docker rmi -f $$(docker images -q) || true
	docker volume rm $$(docker volume ls -q) || true
