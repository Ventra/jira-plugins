package ut.ru.ventra.jira.plugins;

import org.junit.Test;
import ru.ventra.jira.plugins.MyPluginComponent;
import ru.ventra.jira.plugins.MyPluginComponentImpl;

import static org.junit.Assert.assertEquals;

public class MyComponentUnitTest
{
    @Test
    public void testMyName()
    {
        MyPluginComponent component = new MyPluginComponentImpl(null);
        assertEquals("names do not match!", "myComponent",component.getName());
    }
}