
# 定義變數
MVN = ./mvnw
JAVA = java
JAR_FILE = target/ProSync-0.0.1-SNAPSHOT.jar

# 默認目標
.PHONY: all
all: clean build run

# 清理專案
.PHONY: clean
clean:
	$(MVN) clean

# 構建專案
.PHONY: build
build:
	$(MVN) package

# 運行專案
.PHONY: run
run:
	$(JAVA) -jar $(JAR_FILE)

# 運行項目（開發模式，啟用devtools）
.PHONY: dev
dev:
	$(MVN) spring-boot:run

# 資料庫遷移
.PHONY: migrate
migrate:
	$(MVN) flyway:migrate

# 停止專案（如果使用了背景進程）
.PHONY: stop
stop:
	@pkill -f '$(JAR_FILE)'

# 重新運行專案
.PHONY: restart
restart: stop run
