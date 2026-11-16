package io.github.center2055.developermcp.tools;

/** A problem the AI should see and can fix (bad path, missing argument, file too big, ...). */
public class ToolException extends Exception {

    public ToolException(String message) {
        super(message);
    }
}
