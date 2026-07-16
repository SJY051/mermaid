package com.mermaid.facility;

/** Admission boundary for calls that share the National Medical Center credential. */
interface NmcCallQuota {

    boolean tryAcquire(NmcCallKind kind);
}
