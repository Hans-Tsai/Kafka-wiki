localSetup:
	docker-compose -f ./conduktor-platform/docker-compose.yml --project-directory ./ up -d --build
localTearDown:
	docker-compose -f ./conduktor-platform/docker-compose.yml --project-directory ./ down