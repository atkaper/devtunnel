<?php 
  // Start this using the chunked-server.sh script.

  header('Content-Type: text/plain');

  // Flush data, to start chunking instead of using content-legth in response.
  flush();

  // Send some data
  $fp = fopen('README.md', "rb");
  fpassthru($fp);

  // Exit
  return;
?>
