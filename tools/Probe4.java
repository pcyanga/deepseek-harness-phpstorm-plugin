import com.intellij.ui.jcef.JBCefBrowser;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefQueryHandler;
import org.cef.handler.CefQueryHandlerAdapter;

public class Probe4 {
  public void x(JBCefBrowser b) {
    b.getJBCefClient().addEventHandler(CefQueryHandler.class, new CefQueryHandlerAdapter() {
      @Override
      public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                             String request, boolean persistent, CefQueryCallback callback) {
        System.out.println("js-query: " + request);
        callback.success("");
        return true;
      }
    });
  }
}
