docker run -ti --rm --name mginx-proxy -p 8888:80 -v `pwd`/default.conf:/etc/nginx/conf.d/default.conf nginx 
