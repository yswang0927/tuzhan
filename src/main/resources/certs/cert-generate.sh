# 1. 生成服务器密钥和证书
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore server.p12 -validity 3650 \
  -dname "CN=localhost, OU=Tuzhan, O=Tuzhan, C=CN" \
  -storepass tuzhan@2026

# 2. 生成客户端 CA（可选，推荐）
openssl genrsa -out client-ca.key 4096
openssl req -new -x509 -days 3650 -key client-ca.key -out client-ca.crt -subj "/CN=ClientCA"

# 3. 生成客户端证书（CN 就是登录用户名）
openssl genrsa -out client.key 2048
openssl req -new -key client.key -out client.csr -subj "/CN=zhangsan"          # ← 这里的 CN 就是登录用户名
openssl x509 -req -in client.csr -CA client-ca.crt -CAkey client-ca.key -CAcreateserial -out client.crt -days 365

# 4. 把客户端证书导入 truststore
keytool -importcert -alias client-ca -file client-ca.crt -keystore truststore.p12 -storetype PKCS12 -storepass tuzhan@2026 -noprompt