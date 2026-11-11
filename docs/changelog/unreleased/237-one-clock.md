---
section: Changed
---

- The write and repair paths now compute the Mission lock's wait from the same clock as the write budget's deadline. Production
  behaviour is unchanged; a test with a fake clock now gets the right wait (#237; #231 item 4.8).
