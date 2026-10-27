package ai.lmctl.lmfiles;

/** Injectable clock only for deterministic local tests; no production time override. */
interface LeaseClock {
    long wallMillis();
    long nanos();
    LeaseClock SYSTEM=new LeaseClock(){public long wallMillis(){return System.currentTimeMillis();}public long nanos(){return System.nanoTime();}};
}
