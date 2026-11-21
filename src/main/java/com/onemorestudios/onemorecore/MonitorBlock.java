package com.onemorestudios.onemorecore;

/** A one-block screen for narrow places: the TV made small. Monitors side by side join into one picture. */
public class MonitorBlock extends MenuBoardBlock {
    public MonitorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean wide() {
        return false;
    }
}
