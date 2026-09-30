import UIKit
import WebKit

final class MainViewController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate {
    private var webView: WKWebView!
    private var splashView: UIView?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        let contentController = WKUserContentController()
        contentController.add(self, name: "radio")

        let config = WKWebViewConfiguration()
        config.userContentController = contentController
        config.allowsInlineMediaPlayback = true

        webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.scrollView.backgroundColor = .black
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)

        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.topAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])

        RadioManager.shared.stateChanged = { [weak self] active, message in
            self?.updateWebState(active: active, message: message)
        }

        showSplash()
        loadLocalUI()
    }

    private func loadLocalUI() {
        guard let url = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "Resources") else {
            return
        }
        webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
    }

    private func showSplash() {
        let overlay = UIView(frame: view.bounds)
        overlay.backgroundColor = .black
        overlay.translatesAutoresizingMaskIntoConstraints = false

        let imageView = UIImageView()
        imageView.image = UIImage(named: "AppLogo")
        imageView.contentMode = .scaleAspectFit
        imageView.translatesAutoresizingMaskIntoConstraints = false

        let title = UILabel()
        title.text = "Plataforma Livre"
        title.textColor = .white
        title.font = .systemFont(ofSize: 26, weight: .semibold)
        title.textAlignment = .center
        title.translatesAutoresizingMaskIntoConstraints = false

        let subtitle = UILabel()
        subtitle.text = "Centro Cultural UERJ"
        subtitle.textColor = .lightGray
        subtitle.font = .systemFont(ofSize: 14, weight: .medium)
        subtitle.textAlignment = .center
        subtitle.translatesAutoresizingMaskIntoConstraints = false

        overlay.addSubview(imageView)
        overlay.addSubview(title)
        overlay.addSubview(subtitle)
        view.addSubview(overlay)

        NSLayoutConstraint.activate([
            overlay.topAnchor.constraint(equalTo: view.topAnchor),
            overlay.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            overlay.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            imageView.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),
            imageView.centerYAnchor.constraint(equalTo: overlay.centerYAnchor, constant: -55),
            imageView.widthAnchor.constraint(equalToConstant: 220),
            imageView.heightAnchor.constraint(equalToConstant: 220),

            title.topAnchor.constraint(equalTo: imageView.bottomAnchor, constant: 18),
            title.leadingAnchor.constraint(equalTo: overlay.leadingAnchor, constant: 20),
            title.trailingAnchor.constraint(equalTo: overlay.trailingAnchor, constant: -20),

            subtitle.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 8),
            subtitle.leadingAnchor.constraint(equalTo: overlay.leadingAnchor, constant: 20),
            subtitle.trailingAnchor.constraint(equalTo: overlay.trailingAnchor, constant: -20)
        ])

        splashView = overlay
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
            UIView.animate(withDuration: 0.25, animations: {
                self?.splashView?.alpha = 0
            }, completion: { _ in
                self?.splashView?.removeFromSuperview()
                self?.splashView = nil
            })
        }
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "radio",
              let body = message.body as? [String: Any],
              let action = body["action"] as? String else { return }

        switch action {
        case "play":
            let volume = Float((body["volume"] as? Double) ?? 0.85)
            RadioManager.shared.play(volume: volume)
        case "stop":
            RadioManager.shared.stop()
        case "volume":
            let volume = Float((body["volume"] as? Double) ?? 0.85)
            RadioManager.shared.setVolume(volume)
        case "state":
            updateWebState(
                active: RadioManager.shared.isActive,
                message: RadioManager.shared.currentMessage
            )
        default:
            break
        }
    }

    private func updateWebState(active: Bool, message: String) {
        guard webView != nil else { return }
        let safeMessage = message
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
            .replacingOccurrences(of: "\n", with: "\\n")
        let js = "window.setNativeState && window.setNativeState(\(active), '\(safeMessage)');"
        DispatchQueue.main.async { [weak self] in
            self?.webView.evaluateJavaScript(js)
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        updateWebState(
            active: RadioManager.shared.isActive,
            message: RadioManager.shared.currentMessage
        )
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        if let url = navigationAction.request.url,
           let scheme = url.scheme?.lowercased(),
           (scheme == "http" || scheme == "https"),
           navigationAction.navigationType == .linkActivated {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    deinit {
        webView?.configuration.userContentController.removeScriptMessageHandler(forName: "radio")
    }
}
