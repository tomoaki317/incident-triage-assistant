# Incident Triage Assistant

AIを活用したWebアプリ障害の一次切り分け支援ツール。

実装仕様は [docs/specification.md](docs/specification.md)、実装方針は
[docs/implementation-plan.md](docs/implementation-plan.md) を参照してください。

## 現在の実装範囲

AI未接続のJSON出力契約検証と、入力検証・マスキング・送信内容確認用サービスを実装しています。

- Java 21 / Spring Boot 4.1.1 / Maven Wrapper 3.9.11。
- 仕様7章のJSON Schema（Draft 2020-12）、record DTO、enum。
- 厳格なJSON解析、構造検証、入力・ログ・要素IDの参照整合性検証。
- 原因候補提示・情報不足・対象外の合成入力と正常応答、および異常系テスト。

`PreviewService.preview(IncidentInput)` は全入力項目を検証し、マスキング済み入力、
ログ各行に対応する行ID（末尾の空行を含む）、送信先・目的・注意事項を返します。
元入力とは別の `MaskedIncidentInput` 型を使い、置換対応表は処理後に保持しません。
未入力の補足情報は「不明」、ログなしは空文字と空の行ID一覧で表します。
検証エラーは `InputValidationException.fieldErrors()` で項目IDと固定メッセージを確認できます。

`POST /api/previews` に `Content-Type: application/json` で `IncidentInput` を送ると、
上記の `PreviewResponse` をJSONで返します（200）。ローカル利用向けに127.0.0.1へバインドします。
入力検証エラー・不正JSONは400、Content-Type不正は415、想定外エラーは500です。
エラーは `request_id`、`code`、`message`、`field_errors` の共通形式で、本文・内部例外は返しません。
成功・エラーとも `Cache-Control: no-store` を付けます。
Spring WebのログはINFOに設定し、DEBUG/TRACEで本文や例外詳細が記録されることを防ぎます。

静的画面では障害事象・ログ・ログ取得状況を入力し、文字数とマスキング後の内容を確認できます。
通信中は送信ボタンを無効にし、入力変更時に旧プレビューを消去します。
入力・応答はブラウザの永続ストレージやCookieに保存せず、再読み込み・画面離脱・戻る操作でクリアします。
APIエラーは画面側の固定メッセージで表示し、内部例外や生のエラー本文は表示しません。

`POST /api/previews` は `preview_id`（UUID v4）と `expires_at`（UTC）を返します。
`PreviewService.create(input, owner)` で作成し、後続処理は `get(previewId, owner)` から
同じマスキング済み内容・根拠参照集合・AI未接続設定版を取得できます。元入力・置換対応表は保持しません。
保持期間は作成から5分、単一プロセスのメモリ上で最大100件です。上限時は429となります。
取得時は必ず期限と所有セッションを検証し、期限切れ・不明ID・他セッションは同じ410で拒否します。
期限切れは新規作成・該当ID取得時と、1分間隔の定期処理で削除します。再起動すると全件失われます。
`DELETE /api/previews/{id}` は所有セッションのプレビューを削除します（204）。
画面の編集・クリア・離脱でも削除を試み、通信できない場合は期限切れで破棄します。
Cookieには本文を含まない匿名セッションIDだけを使用します（HttpOnly・SameSite=Strict）。
作成は利用者が確認するための候補の発行であり、解析実行への同意を自動的に記録するものではありません。

`POST /api/analyses` に同じ匿名セッションのCookieと `{"preview_id":"作成時のID"}` を送ると、
保持済みのマスキング済み入力だけを `AiClient` に渡します。現在の実装は通信しない `StubAiClient` です。
応答JSONも既存のSchema・参照・判断状態検証を通し、成功時は `request_id` と `result` を返します（200）。
Stubは事象に「ネットワーク機器の侵害調査」があれば対象外、そうでなくログに `Duplicate entry` があれば
原因候補提示、それ以外は情報不足を返します。実際の診断ではなく、フロー検証用の決定的な結果です。
ID不明・期限切れ・他セッションは410、同一プレビューの分析中は409、応答契約違反は502、想定外例外は500です。
成功・エラーともno-storeとし、生応答や内部例外は返しません。
分析を開始したプレビューは成功・失敗とも終了時に削除し、再試行には新しいプレビューを必要とします。
所有関係の検証失敗や重複拒否では、実行中または他ユーザーのプレビューを削除しません。
分析リクエストにはUUID v4の `execution_id` を指定できます。省略時はサーバーが発行し、成功応答へ返します。
通信失敗後も同じ要求を識別するには、呼び出し元で発行したIDを指定してください。
実行IDは匿名セッション・preview_idに紐付けて分析前に原子的に確保し、再利用は409で拒否します。
本文なしの実行記録はAsia/Tokyoの当日終了までメモリに保持し、上限1000件で受付を停止します。
全体同時実行は2件、待機キューなし、超過は429です。処理全体は60秒で504となり、クライアントへ中断を要求します。
中断に応じない処理が残る場合、その処理の終了まで実行枠を保持します。外部の処理・課金停止は保証できません。
`triage.analysis.*` で全体timeout（最大60秒）、client-timeout（初期50秒）、max-input-tokens（32000）、
max-output-tokens（8000）、max-cost-usd（0.03）、max-execution-records（1000）を設定します。
クライアントには残り時間以内のtimeout・出力上限・費用上限・自動リトライ0回を渡します。
実AIアダプターは設定付きの `AiClient.analyze(input, options)` を実装し、SDKの自動再試行も無効化する必要があります。
`AiResponse` は利用量を返せますが、今回は実費計算・予算予約は行いません。未知の利用量はnullです。
`TokenCounter` の現実装はStub用のJSONコードポイント数で、モデルのトークン数ではありません。
実AI接続時は指示・Schemaを含む全送信内容を計数するモデル固有実装へ置き換えてください。
AI側429/5xxは503、timeoutは504、契約検証失敗は502です。アプリ・クライアントの自動リトライはありません。
再起動を跨ぐ実行制御・運用状態ファイル・日次予算・公開デモの回数制限は今回の対象外です。

AI接続、分析結果UI・引き継ぎ文面生成、公開デモのサンプル受付、RAG、認証、履歴保存は未実装です。
送信先は未選定と表示し、外部通信は行いません。トークン上限はモデル選定時に実装します。
障害元の想定はSpring Boot＋MySQLのREST APIですが、本ツールのテストにDBは不要です。

## テスト

画面は、下記のJava・Maven環境設定後に `.\mvnw.cmd spring-boot:run` で起動し、
`http://127.0.0.1:8080/` で利用できます。AI解析ボタンはありません。

JDK 21を用意し、JAVA_HOMEをJDKのディレクトリに設定してください。
Mavenの個別インストールは不要です。初回のみWrapperと依存ライブラリの取得にネットワーク接続が必要です。
テスト自体はAI APIキーや外部サービスを使用しません。

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd test
```

Unix系では `./mvnw test` を使用します。結果は `target/surefire-reports/` に出力されます。

この作業環境用に `.tools/` 内へJDKを取得している場合は、次の設定でも実行できます。
`.tools/` と `.maven-user-home/` はGit管理対象外です。

```powershell
$env:JAVA_HOME = (Get-ChildItem .tools -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:MAVEN_USER_HOME = "$PWD/.maven-user-home"
$env:MAVEN_OPTS = "-Dmaven.repo.local=$PWD/.maven-user-home/repository"
.\mvnw.cmd test
```

## 検証の入口

`TriageResultValidator.validate(json, sourceReferences)` が、構造・参照の検証に成功した場合だけ
`TriageResult` を返します。失敗時は固定コードの `ContractViolationException` を返し、
元の本文・パーサー例外・Schema診断を例外やログへ含めません。

`SourceReferences` には呼び出し側が実入力から作成した項目ID・マスキング後のログ行IDだけを渡します。
未入力を「不明」と補完した項目は含めません。応答自身が申告した参照集合を信用しないでください。

Schemaはクラスパスの固定ファイルのみを読み、検証時に外部Schemaを取得しません。
検証は構造と参照の実在を確認するもので、原因仮説の正しさを保証するものではありません。
