# Kafka for Wiki

This is a companion repository for the [Kafka for Beginners course](https://links.datacumulus.com/apache-kafka-coupon) by Conduktor

## Content
- Wikimedia Producer
- OpenSearch Consumer

## How to start
```sh
git clone https://github.com/Tunahaha/Kafka-wiki.git
```

### container
```sh
make localSetup
```

### producer-wikimedia
```sh
run WikimediaChangesProducer.java
```
### consumer-openSearch
We recommend to run the openSearch using the container or free [openSearch|https://bonsai.io] service.
You need to change connString in OpenSearchConsumer.java to your openSearch service.
```sh
run OpenSearchConsumer.java
```

