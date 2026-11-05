import UIKit
import CryptoKit
import Photos

/// A separate sandbox containing only generated acceptance files.
/// It has no Agent connection, shared container, credential or network client.
@main final class FixtureApp: UIResponder, UIApplicationDelegate {
  func application(_ application: UIApplication, configurationForConnecting session: UISceneSession,
                   options: UIScene.ConnectionOptions) -> UISceneConfiguration {
    let configuration = UISceneConfiguration(name: "Synthetic files", sessionRole: session.role)
    configuration.delegateClass = FixtureScene.self
    return configuration
  }
}

final class FixtureScene: UIResponder, UIWindowSceneDelegate {
  var window: UIWindow?

  func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
    guard let scene = scene as? UIWindowScene else { return }
    let args = ProcessInfo.processInfo.arguments
    if let index=args.firstIndex(of:"--easywork-photo-fixture-case"),args.indices.contains(index+1),
       let id=UUID(uuidString:args[index+1]),id.uuidString.lowercased()==args[index+1] {
      let window=UIWindow(windowScene:scene)
      window.rootViewController=PhotoFixtureController(caseID:id.uuidString.lowercased())
      window.makeKeyAndVisible();self.window=window;return
    }
    let controller = UIViewController()
    controller.view.backgroundColor = .systemBackground
    let label = UILabel()
    label.numberOfLines = 0
    label.textAlignment = .center
    label.translatesAutoresizingMaskIntoConstraints = false
    do {
      let args = ProcessInfo.processInfo.arguments
      guard let index = args.firstIndex(of: "--easywork-file-fixture-case"), args.indices.contains(index + 1),
            let id = UUID(uuidString: args[index + 1]), id.uuidString.lowercased() == args[index + 1] else {
        throw FixtureError.invalidCase
      }
      try prepare(caseID: id.uuidString.lowercased())
      label.text = "EasyWork 验收文件\n仅包含本次生成的两份合成文件\n\(id.uuidString.lowercased())"
    } catch {
      label.text = "EasyWork 验收文件\n未准备文件：\(error)"
    }
    controller.view.addSubview(label)
    NSLayoutConstraint.activate([
      label.leadingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
      label.trailingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
      label.centerYAnchor.constraint(equalTo: controller.view.centerYAnchor)
    ])
    let window = UIWindow(windowScene: scene)
    window.rootViewController = controller
    window.makeKeyAndVisible()
    self.window = window
  }

  private func prepare(caseID: String) throws {
    let manager = FileManager.default
    let documents = manager.urls(for: .documentDirectory, in: .userDomainMask)[0]
    let directory = documents.appendingPathComponent("easywork-files-" + caseID, isDirectory: true)
    let receiptURL = documents.appendingPathComponent("fixture-" + caseID + ".json")
    // A case is prepared once. An unexpected prior directory is never overwritten.
    guard !manager.fileExists(atPath: directory.path), !manager.fileExists(atPath: receiptURL.path) else {
      throw FixtureError.caseAlreadyExists
    }
    try manager.createDirectory(at: directory, withIntermediateDirectories: false,
                                attributes: [.posixPermissions: 0o700])
    var files: [[String: Any]] = []
    let nonce = String(caseID.replacingOccurrences(of: "-", with: "").prefix(8))
    for suffix in ["a", "b"] {
      let name = "easywork-" + nonce + "-" + suffix + ".txt"
      let line = name + ":external-synthetic-source:" + caseID + "\n"
      let data = Data(String(repeating: line, count: 800).utf8)
      let url = directory.appendingPathComponent(name)
      try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      try manager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
      files.append(["name": name, "sourcePath": url.path, "sizeBytes": data.count,
                    "sha256": SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()])
    }
    let receipt: [String: Any] = ["version": 1, "caseId": caseID,
      "bundleId": Bundle.main.bundleIdentifier ?? "", "directory": directory.path,
      "syntheticFilesOnly": true, "files": files]
    try JSONSerialization.data(withJSONObject: receipt, options: [.sortedKeys])
      .write(to: receiptURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    try manager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: receiptURL.path)
  }
}

private enum FixtureError: Error { case invalidCase, caseAlreadyExists }

/// Opt-in test-only photo source. Add-only consent never reads an existing asset.
private final class PhotoFixtureController: UIViewController {
  private let caseID:String
  private let label=UILabel(),button=UIButton(type:.system)
  private var sources:[(name:String,data:Data)]=[]
  private var report:[String:Any]=[:]
  private var receipt:URL!
  init(caseID:String){self.caseID=caseID;super.init(nibName:nil,bundle:nil)}
  required init?(coder:NSCoder){fatalError("Explicit fixture case required")}
  private func save(_ stage:String)throws{report["stage"]=stage;try JSONSerialization.data(withJSONObject:report,options:[.sortedKeys]).write(to:receipt,options:[.atomic,.completeFileProtectionUntilFirstUserAuthentication])}
  override func viewDidLoad(){
    super.viewDidLoad();view.backgroundColor = .systemBackground
    label.numberOfLines=0;label.textAlignment = .center;label.translatesAutoresizingMaskIntoConstraints=false
    button.setTitle("添加 6 张合成验收图片",for:.normal);button.accessibilityIdentifier="add-six-controlled-photos";button.translatesAutoresizingMaskIntoConstraints=false
    view.addSubview(label);view.addSubview(button)
    NSLayoutConstraint.activate([label.leadingAnchor.constraint(equalTo:view.safeAreaLayoutGuide.leadingAnchor,constant:24),label.trailingAnchor.constraint(equalTo:view.safeAreaLayoutGuide.trailingAnchor,constant:-24),label.centerYAnchor.constraint(equalTo:view.centerYAnchor,constant:-60),button.centerXAnchor.constraint(equalTo:view.centerXAnchor),button.topAnchor.constraint(equalTo:label.bottomAnchor,constant:32)])
    do{
      let documents=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0]
      receipt=documents.appendingPathComponent("photo-fixture-"+caseID+".json")
      let folder=documents.appendingPathComponent("easywork-photos-"+caseID,isDirectory:true)
      guard !FileManager.default.fileExists(atPath:receipt.path),!FileManager.default.fileExists(atPath:folder.path)else{throw FixtureError.caseAlreadyExists}
      try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:false,attributes:[.posixPermissions:0o700])
      for index in 1...6{
        let size=CGSize(width:320,height:240);let format=UIGraphicsImageRendererFormat();format.scale=1;format.opaque=true
        let image=UIGraphicsImageRenderer(size:size,format:format).image{ctx in
          UIColor(red:CGFloat(20+index*25)/255,green:60/255,blue:160/255,alpha:1).setFill();ctx.fill(CGRect(origin:.zero,size:size))
          UIColor(red:CGFloat(235-index*25)/255,green:195/255,blue:95/255,alpha:1).setFill();ctx.fill(CGRect(x:160,y:0,width:160,height:240))
          ("EW "+String(caseID.prefix(8))+" / "+String(index)as NSString).draw(at:CGPoint(x:12,y:100),withAttributes:[.font:UIFont.monospacedSystemFont(ofSize:22,weight:.bold),.foregroundColor:UIColor.white])
        }
        let data=image.pngData()!,name="easywork-"+String(caseID.prefix(8))+"-photo-"+String(index)+".png"
        try data.write(to:folder.appendingPathComponent(name),options:[.atomic,.completeFileProtectionUntilFirstUserAuthentication]);sources.append((name,data))
      }
      report=["caseId":caseID,"bundleId":Bundle.main.bundleIdentifier!,"nativeProcessId":ProcessInfo.processInfo.processIdentifier,"onlySyntheticPhotos":true,"existingPhotoReads":0,"requestedAccess":"addOnly","actualPhotoWrites":0,"sourceFolder":folder.lastPathComponent,"files":sources.enumerated().map{index,item in["index":index+1,"name":item.name,"sizeBytes":item.data.count,"sha256":SHA256.hash(data:item.data).map{String(format:"%02x",$0)}.joined()]as[String:Any]}]
      try save("awaiting-actual-add-only-photo-consent-action")
      label.text="EasyWork 合成图片夹具\n"+String(caseID.prefix(8))+"\n只添加六张测试图片，不读取已有照片。"
      button.addTarget(self,action:#selector(addPhotos),for:.touchUpInside)
    }catch{label.text="未准备图片：\(error)";button.isEnabled=false}
  }
  @objc private func addPhotos(){
    guard button.isEnabled else{return};button.isEnabled=false
    do{try save("actual-add-only-authorization-requested-once")}catch{label.text="无法保存准备回执";return}
    PHPhotoLibrary.requestAuthorization(for:.addOnly){[weak self]status in
      DispatchQueue.main.async{
        guard let self else{return};self.report["actualAuthorizationStatus"]=status.rawValue
        guard status == .authorized else{self.report["status"]="photo-add-only-not-authorized";try?self.save("ended-without-photo-write");self.label.text="仅添加照片权限未获准；未写照片";return}
        var identifiers:[String]=[]
        PHPhotoLibrary.shared().performChanges({
          for item in self.sources{
            let creation=PHAssetCreationRequest.forAsset();let options=PHAssetResourceCreationOptions();options.originalFilename=item.name
            creation.addResource(with:.photo,data:item.data,options:options);creation.creationDate=Date()
            if let id=creation.placeholderForCreatedAsset?.localIdentifier{identifiers.append(id)}
          }
        }){success,error in DispatchQueue.main.async{
          self.report["createdAssetIdentifiers"]=identifiers;self.report["actualPhotoWrites"]=success ? 6:0;self.report["status"]=success ? "prepared-six-physical-synthetic-photo-assets-once":"photo-transaction-failed-no-automatic-retry"
          if let error{self.report["failure"]=error.localizedDescription}
          try?self.save("completed-photo-source-preparation");self.label.text=success ? "已添加六张合成测试图片\n"+String(self.caseID.prefix(8))+"\n照片选择及产品验收尚未执行。":"照片写入失败，未自动重试"
        }}
      }
    }
  }
}
