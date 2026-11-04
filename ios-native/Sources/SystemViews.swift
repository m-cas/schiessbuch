import SwiftUI
import MessageUI
import UIKit

/// iOS mail composer with attachments. Falls back to the share sheet when Mail isn't set up.
struct MailComposer: UIViewControllerRepresentable {
    struct Attachment { var data: Data; var mime: String; var name: String }
    var subject: String
    var body: String
    var recipients: [String] = []
    var attachments: [Attachment] = []
    var onFinish: () -> Void

    static var canSend: Bool { MFMailComposeViewController.canSendMail() }

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let vc = MFMailComposeViewController()
        vc.mailComposeDelegate = context.coordinator
        vc.setSubject(subject)
        vc.setMessageBody(body, isHTML: false)
        if !recipients.isEmpty { vc.setToRecipients(recipients) }
        for a in attachments { vc.addAttachmentData(a.data, mimeType: a.mime, fileName: a.name) }
        return vc
    }
    func updateUIViewController(_ vc: MFMailComposeViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }
    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        let onFinish: () -> Void
        init(onFinish: @escaping () -> Void) { self.onFinish = onFinish }
        func mailComposeController(_ controller: MFMailComposeViewController, didFinishWith result: MFMailComposeResult, error: Error?) { onFinish() }
    }
}

/// The system share sheet.
struct ShareSheet: UIViewControllerRepresentable {
    var items: [Any]
    var subject: String? = nil
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        if let subject { vc.setValue(subject, forKey: "subject") }
        return vc
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

/// The system camera for photographing a target.
struct CameraPicker: UIViewControllerRepresentable {
    var onImage: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    static var isAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let vc = UIImagePickerController()
        vc.sourceType = .camera
        vc.delegate = context.coordinator
        return vc
    }
    func updateUIViewController(_ vc: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ p: CameraPicker) { parent = p }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let img = info[.originalImage] as? UIImage { parent.onImage(img) }
            parent.dismiss()
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}

/// What to send by mail or share: built from sessions or a backup file.
struct OutgoingMail: Identifiable {
    let id = UUID()
    var subject: String
    var body: String
    var attachments: [MailComposer.Attachment]
    var shareItems: [Any]
}

/// Presents mail (or the share sheet when Mail isn't available) for an OutgoingMail.
struct MailOrShare: ViewModifier {
    @Binding var item: OutgoingMail?
    @AppStorage("mailto") private var lastRecipient = ""
    func body(content: Content) -> some View {
        content.sheet(item: $item) { m in
            if MailComposer.canSend {
                MailComposer(subject: m.subject, body: m.body, recipients: lastRecipient.isEmpty ? [] : [lastRecipient], attachments: m.attachments) { item = nil }
                    .ignoresSafeArea()
            } else {
                ShareSheet(items: [m.body] + m.shareItems, subject: m.subject)
                    .presentationDetents([.medium, .large])
            }
        }
    }
}
extension View {
    func mailOrShare(_ item: Binding<OutgoingMail?>) -> some View { modifier(MailOrShare(item: item)) }
}

/// A URL that can be presented in a sheet.
struct SharedFile: Identifiable { let id = UUID(); let url: URL }
