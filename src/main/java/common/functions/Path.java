package common.functions;

import java.util.List;

/**
 * A Path corresponds to a code path in a FunctionContext.
 *
 * It corresponds to a set of line numbers.
 */
public class Path
{
    private List<Integer> path;

    public Path ()
    {
        path = new java.util.ArrayList<>();
    }

    List<Integer> getPath()
    {
        return path;
    }

    public void addLine(int line)
    {
        path.add(line);
    }

}
