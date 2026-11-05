import Foundation

// Transport doubles only. Tests compile the production EasyWorkReading.swift boundary unchanged.
struct EasyWorkTarget: Codable, Equatable, Sendable {
  var account: AgentAccount; var desktopDeviceId: String; var workspaceId: String; var runtimeId: String
  var dictionary: [String: Any] { try! JSONSerialization.jsonObject(with: JSONEncoder().encode(self)) as! [String: Any] }
}
struct AgentConnection: Sendable { var target: AgentRuntimeTarget }
struct Client: Sendable { var id: String; var agentConnection: AgentConnection?; var readingScopeId: String?; var readingTarget: AgentRuntimeTarget? }
struct ReadAcknowledgement { var target: AgentRuntimeTarget; var hostId: String; var threadId: String; var readerId: String; var read: ReadPosition }
enum HostServiceError: Error { case invalidResponse,wrongHost,unavailable,message(String),rejected(Int,String) }
func easyWorkJSON(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject:value,options:[.sortedKeys,.withoutEscapingSlashes,.fragmentsAllowed]) }
func easyWorkObject(_ data: Data) throws -> [String: Any] { guard let value=try JSONSerialization.jsonObject(with:data) as? [String: Any] else { throw HostServiceError.invalidResponse }; return value }
struct EasyWorkAgentClient {}
actor EasyWorkSessions { static let shared=EasyWorkSessions();func session(_ client: Client) async throws -> EasyWorkRemoteSession { throw HostServiceError.unavailable } }
actor EasyWorkRemoteSession {
  nonisolated let target: EasyWorkTarget
  var mobileDeviceId="mobile";var epoch="epoch";var requests:[(String,Data,String)]=[]
  var responses:[Result<Data,Error>]
  init(_ target: EasyWorkTarget,_ responses:[Result<Data,Error>]) { self.target=target;self.responses=responses }
  func request(_ method: String,_ params:[String: Any],id:String=UUID().uuidString) async throws -> Data {
    requests.append((method,try easyWorkJSON(params),id));guard !responses.isEmpty else { throw HostServiceError.unavailable };return try responses.removeFirst().get()
  }
}
@main struct ReadingBoundary {
 static func main() async throws {
  let target=EasyWorkTarget(account:AgentAccount(authority:"https://account.example",subject:"human"),desktopDeviceId:"host",workspaceId:"workspace",runtimeId:"runtime")
  let machine=AgentRuntimeTarget(agentId:"easywork",hostId:"host",workspaceId:"workspace",runtimeId:"runtime",account:target.account)
  let reader=String(repeating:"a",count:64),thread="thread"
  let client=Client(id:"connection-alias",agentConnection:AgentConnection(target:machine),readingScopeId:reader,readingTarget:machine)
  let position=ReadPosition(revision:7,messageId:"reply",contentVersion:String(repeating:"b",count:64))
  let rawPosition:[String:Any] = ["revision":7,"messageId":"reply","contentVersion":position.contentVersion]
  func response(read:Bool=false)->[String:Any] {
   var state:[String:Any] = ["version":1,"latest":rawPosition,"readerId":reader];if read { state["read"]=rawPosition }
   return ["target":target.dictionary,"hostId":"host","threadId":thread,"readerId":reader,"readRevision":7,"readState":state]
  }
  func decode(_ value:[String:Any]) throws -> EasyWorkReading.Summary { try EasyWorkReading.decode(value,client:client,target:target,threadId:thread,readerId:reader) }
  func reject(_ body:() throws -> Void) { do { try body();preconditionFailure("Invalid identity/version must reject") } catch {} }
  let originalSummary=try decode(response());precondition(originalSummary.state?.latest==position)
  for value:Any in [true,"7",7.5,-1,9_007_199_254_740_992] { var row=response();row["readRevision"]=value;reject { _ = try decode(row) } }
  for (key,value) in [("hostId","connection-alias"),("threadId","other-thread"),("readerId",String(repeating:"c",count:64))] { var row=response();row[key]=value;reject { _ = try decode(row) } }
  var extra=response();extra["extra"]=true;reject { _ = try decode(extra) }
  var extraTarget=response(),wrong=target.dictionary;wrong["extra"]=true;extraTarget["target"]=wrong;reject { _ = try decode(extraTarget) }
  var empty=response();empty["readState"]=NSNull();empty["readRevision"]=8;let emptySummary=try decode(empty);precondition(emptySummary.counter==8)
  let list:[String:Any] = ["target":target.dictionary,"hostId":"host","readerId":reader,"items":[["threadId":thread,"readRevision":7,"readState":response()["readState"]!],["threadId":"empty","readRevision":8,"readState":NSNull()]]]
  let decoded=try EasyWorkReading.decodeList(list,client:client,target:target,threadIds:[thread,"empty"],readerId:reader)
  precondition(decoded.count==2 && decoded[1].counter==8 && decoded[1].state==nil)
  reject { _ = try EasyWorkReading.decodeList(list,client:client,target:target,threadIds:["empty",thread],readerId:reader) }
  let originalText="[report](relative/report.txt)",displayText="[report](codex-attachment://open?id=resource)"
  let originalVersion=ReadPosition.contentVersion(messageId:"link",text:originalText,status:"completed")
  let projected=ReadContentProjection(sourceVersion:originalVersion,displayVersion:ReadPosition.contentVersion(messageId:"link",text:displayText,status:"completed"))
  let linkPosition=ReadPosition(revision:9,messageId:"link",contentVersion:originalVersion)
  precondition(!linkPosition.matches(messageId:"link",text:displayText,status:"completed"))
  precondition(linkPosition.matches(messageId:"link",text:displayText,status:"completed",projection:projected))
  precondition(!linkPosition.matches(messageId:"link",text:displayText+"unseen change",status:"completed",projection:projected))
  func encoded(_ value:[String:Any])->Result<Data,Error> { .success(try! easyWorkJSON(value)) }
  // Real production control flow: a lost original acknowledgement is reconciled with a read, with one write only.
  let channel=EasyWorkRemoteSession(target,[encoded(response()),.failure(HostServiceError.unavailable),encoded(response(read:true))])
  let accepted=try await EasyWorkReading.acknowledge(channel,client:client,threadId:thread,readerId:reader,position:position)
  precondition(accepted.hostId==client.id && accepted.read==position)
  let requests=await channel.requests
  precondition(requests.map { $0.0 }==["thread/read-state","thread/acknowledge-read","thread/read-state"])
  precondition(requests[1].2.hasPrefix("read-") && requests[1].2.count==69)
  let newer=ReadPosition(revision:8,messageId:"reply",contentVersion:position.contentVersion)
  let stale=EasyWorkRemoteSession(target,[encoded(response())])
  do { _ = try await EasyWorkReading.acknowledge(stale,client:client,threadId:thread,readerId:reader,position:newer);preconditionFailure("Unseen newer content must not be acknowledged") }
  catch HostServiceError.rejected(let status,_) { precondition(status==409) }
  let staleRequests=await stale.requests;precondition(staleRequests.count==1)
  print("EasyWork production Foundation reading boundary passed: strict target/counter, ordered list, empty head, exact original acknowledgement and lost receipt read-only reconciliation; no model or Codex tests.")
 }
}
