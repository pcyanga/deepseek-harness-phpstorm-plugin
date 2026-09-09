import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefJSQuery;

public class Probe3 {
  public void x(JBCefBrowser b) {
    JBCefJSQuery q = JBCefJSQuery.create(b);
    q.addHandler(msg -> {
      System.out.println("js: " + msg);
      return null;
    });
    q.inject("window.__dshReport = function(m){ " + q.getQueryName() + "(m); };");
    q.dispose();
  }
}
