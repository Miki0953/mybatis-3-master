Linux常用命令集锦
=====================================

软件相关
-------------------------------------
* JDK
  * 查看jdk版本 java -version
  * 查看jdk安装路径 which java
* Redis
  * 检查后台进程是否正在运行 ps -ef|grep redis
  * 检测6379端口是否在监听 netstat -lntp|grep 6379
  * 使用redis-cli客户端检测连接是否正常 ./redis-cli -h 127.0.0.1 -p 6379;之后再输入密码： auth 'your password'
* zookeeper
* docker

Linux系统命令
-------------------------------------
* 查看Linux系统内核版本 uname -a (此命令可以查看内核版本和系统是32位还是64位)
* 查看Linux系统位数 uname -m
* 查看当前操作系统版本信息 cat /etc/lsb-release