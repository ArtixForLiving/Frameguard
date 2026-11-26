package com.optimizer.game;

import com.optimizer.game.privilege.ShellResult;

interface IShellService {
    ShellResult runCommand(String command);
    ShellResult runArgs(in String[] args);
    void destroy();
}
