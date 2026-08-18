# 1. 生成服务器密钥和证书
# 可以通过 keytool -list -v -keystore ./server.p12 -storetype PKCS12 查看别名
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore server.p12 -validity 3650 \
  -dname "CN=localhost, OU=Tuzhan, O=Tuzhan, C=CN" \
  -storepass tuzhan@2026

# 2. 签发客户端证书的根 CA
openssl genrsa -out client-ca.key 4096
openssl req -new -x509 -days 3650 -key client-ca.key -out client-ca.crt -subj "/CN=ClientCA"

# 3. 把客户端证书导入 truststore, 服务端信任库，存放信任的客户端 CA，校验客户端证书
keytool -importcert -alias client-ca -file client-ca.crt -keystore truststore.p12 -storetype PKCS12 -storepass tuzhan@2026 -noprompt

# 4.1 生成客户端证书（CN 就是登录用户名）
openssl genrsa -out client.key 2048
openssl req -new -key client.key -out client.csr -subj "/CN=zhangsan"          # ← 这里的 CN 就是登录用户名
openssl x509 -req -in client.csr -CA client-ca.crt -CAkey client-ca.key -CAcreateserial -out client.crt -days 365

# 4.2 提取客户端 p12（浏览器 / HttpClient 使用，重要）
#    openssl 生成的是 crt+key，浏览器需要 p12 格式客户端证书：
openssl pkcs12 -export -in client.crt -inkey client.key -out client.p12 -name zhangsan