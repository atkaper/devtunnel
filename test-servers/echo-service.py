#!/usr/bin/env python3

"""
Very simple HTTP server in python for logging requests, and testing gateway scenarios.
It will listen on localhost:8888.
If you pass in a query string parameter "delay=6" we will sleep for 6 seconds (for example to test timeout error of 5 seconds), any valye in seconds is possible.
if you pass in a query string parameter "status=500" we will respond with http status 500, any numeric status code is possible.
"""
from http.server import BaseHTTPRequestHandler, HTTPServer
import logging
import requests
import json
import re
from urllib.parse import urlparse, parse_qs
import time

class HTTPRequestHandler(BaseHTTPRequestHandler):
  timeout = 3

  def write_response(self, content):
    params = parse_qs(urlparse(self.path).query)

    data = "Echo Service...\n-----------------------\n\nRemoteIp: " + self.client_address[0] + "\nUrl: " + self.requestline + "\n\nRequest Headers:\n\n" + str(self.headers) + "\n"
    if(content):
      try:
        data = data + "\ndata: " + content.decode('utf-8') + "\n"
      except:
        data = data + "\ndata: can not decode to utf8, perhaps binary data... length: " + str(len(content)) + "\n";

    # If you pass in a query string parameter "redirect=http://sometarget/" you will get a redirect response. We ignore delay and status in that case.
    if "redirect" in params:
        self.send_response(302)
        self.send_header('Location', params["redirect"][0])
        self.end_headers()
        return

    # If you pass in a query string parameter "delay=6" we will sleep for 6 seconds (for example to test timeout error of 5 seconds)
    if "delay" in params:
        delay = int(params["delay"][0])
        data = data + "Delay: " + str(delay) + "\n"
        time.sleep(delay)

    # if you pass in a query string parameter "status=500" we will respond with http status 500
    status = 200
    if "status" in params:
        status = int(params["status"][0])
        data = data + "Returning http status: " + str(status) + "\n"

    print(data)

    self.send_response(status) # , "OK")
    self.send_header('Content-Type', 'text/plain')
    self.end_headers()
    self.wfile.write((data).encode("utf-8"))

  # override POST to do GET
  def do_POST(self):
    content_length = int(self.headers.get('content-length', 0))
    try:
      body = self.rfile.read(content_length)
    except:
      print("timeout - probably wrong content-length header?")
      body = "timeout - probably wrong content-length header?".encode('utf-8')
    self.write_response(body)
    return

  # override HEAD to do GET
  def do_HEAD(self):
    self.write_response(body)
    return

  # override PUT to do GET
  def do_PUT(self):
    self.do_POST()
    return

  # override GET method, echo request.
  def do_GET(self):
    # /shutdown will still process the current request, and stops the server afterwards.
    if self.path == "/shutdown":
        self.server.shutdown()
    self.write_response("")
    return


if __name__ == '__main__':
    server_address = ("", 8888)
    httpd = HTTPServer(server_address, HTTPRequestHandler)
    print(f"Server started on 127.0.0.1:8888")
    httpd.serve_forever()
