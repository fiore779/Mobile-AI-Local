//@ timeout=1000 watchdog=0 grace=1500
console.log("blocking, no watchdog (client kill expected)");
__block();
