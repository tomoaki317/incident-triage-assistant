# Incident Triage Assistant

AIを活用したWebアプリ障害の一次切り分け支援ツール。

実装仕様は [docs/specification.md](docs/specification.md)、実装方針は
[docs/implementation-plan.md](docs/implementation-plan.md) を参照してください。

## 現在の実装範囲

入力検証・マスキング・プレビュー管理・分析API・JSON出力契約検証を実装しています。既定は通信しないStubで、OpenAIへの切替は明示設定が必要です。

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
同じマスキング済み内容・根拠参照集合・AI設定版を取得できます。元入力・置換対応表は保持しません。
保持期間は作成から5分、単一プロセスのメモリ上で最大100件です。上限時は429となります。
取得時は必ず期限と所有セッションを検証し、期限切れ・不明ID・他セッションは同じ410で拒否します。
期限切れは新規作成・該当ID取得時と、1分間隔の定期処理で削除します。再起動すると全件失われます。
`DELETE /api/previews/{id}` は所有セッションのプレビューを削除します（204）。
画面の編集・クリア・離脱でも削除を試み、通信できない場合は期限切れで破棄します。
Cookieには本文を含まない匿名セッションIDだけを使用します（HttpOnly・SameSite=Strict）。
作成は利用者が確認するための候補の発行であり、解析実行への同意を自動的に記録するものではありません。

`POST /api/analyses` に同じ匿名セッションのCookieと `{"preview_id":"作成時のID"}` を送ると、
保持済みのマスキング済み入力と根拠参照集合だけを `AiClient` に渡します。既定は通信しない `StubAiClient` です。
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
OpenAIアダプターは `AiClient.analyze(input, sources, options)` を使い、原文や元の対応表を受け取りません。
`AiResponse` は入力・出力トークンと、設定単価による費用（USD、キャッシュ入力割引込み）を返します。
サービスは本文・利用量を永続保存しません。Stubの利用量と費用はnullです。失敗応答の費用を0と見なさないでください。
AI側429/5xxは503、timeoutは504、契約検証失敗は502です。アプリ・クライアントの自動リトライはありません。
再起動を跨ぐ実行制御・運用状態ファイル・日次予算・公開デモの回数制限は今回の対象外です。

分析結果UI・引き継ぎ文面生成、公開デモのサンプル受付、RAG、認証、履歴保存は未実装です。
OpenAIモードのプレビューには送信先モデルと、計数API・生成APIへの送信予定を表示します。
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

## OpenAI接続（ローカルの手動確認用）

通常テストはFake通信だけを使用し、実OpenAIの利用権限・Schema受理・生成品質は手動確認が必要です。
APIキーは `OPENAI_API_KEY` 環境変数だけから取得し、未設定時は通信せず503を返します。
`triage.ai.mode=stub|openai` で切り替えます。モデルは `triage.openai.model`、
単価は `input-usd-per-million` / `cached-input-usd-per-million` / `output-usd-per-million` です。
初期候補は `gpt-4.1-mini-2025-04-14`、100万トークン当たりUSD 0.40 / 0.10 / 1.60。
モデル変更時は単価・利用条件を再確認してください。税・為替・契約割引は計算対象外です。

JDK HttpClientから固定URL `https://api.openai.com/v1/responses/input_tokens` と `/responses` に各1回POSTします。
SDK不使用、リダイレクト禁止、接続再試行無効、アプリの再試行0回。接続上限5秒、計数と生成を合わせて
クライアント50秒以内かつ全体60秒の残り時間以内です。途中終了・拒否・最大出力到達は502になります。
`store:false`、`stream:false`、`truncation:disabled` を指定し、ツール・会話履歴・外部URL取得は使いません。
HTTPのwire/debugログが有効ならアダプターの起動を拒否します。

Structured Outputsには元Schemaから生成した送信用Schemaを `text.format` / `strict:true` で渡します。
未対応の `allOf` 条件だけを送信用から外し、enum/constの型と空配列のitemsを補います。
元Schemaは変更せず、受信後の判断状態・件数・参照検証を省略しません。コードフェンスや自由文の復元はしません。

モデル固有のローカルtokenizerは未導入です。代替として、指示・全入力・行ID・参照集合・送信用Schemaを
含む生成リクエスト全体のUTF-8バイト数で保守的にローカル制限します。32,000超なら計数APIにも送りません。
これは正確なトークン数ではなく、日本語などでは上限以内の入力も拒否します。
通過後もOpenAI計数APIで同じmodel/instructions/input/textを計数し、32,000超または計数失敗なら生成しません。
計数APIもマスキング済み確認内容をOpenAIへ送信する処理です。API内部の整形分をローカル方式だけで保証せず、
実計数を必須にしています。計数APIが利用できない場合、推定値で生成を続行しません。
費用もローカル検査と実計数の両段階で「入力単価×入力数＋出力単価×8,000」を評価し、$0.03超なら生成しません。
最大出力8,000トークンは生成APIへそのまま渡します。利用量はusageから読み、cached_tokensを分けて実費を計算します。

公式資料：[モデル・料金](https://developers.openai.com/api/docs/models/gpt-4.1-mini)、
[Structured Outputsの対応範囲](https://developers.openai.com/api/docs/guides/structured-outputs)、
[入力トークン計数](https://developers.openai.com/api/reference/typescript/resources/responses/subresources/input_tokens/methods/count)。
`store:false` は事業者側の全保存を禁止する保証ではありません。保存期間・学習利用・処理地域・削除条件は
利用アカウントで確認してください。公開デモの条件は未整備です。

### 手動確認手順

1. 上記JDK・Maven環境を設定します。専用の合成データだけを使用してください。
2. 起動用PowerShellでキーを非表示入力し、OpenAIモードで起動します（キーをコマンドへ直書きしません）。

```powershell
$env:OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', (Read-Host 'OpenAI API key' -AsSecureString)).Password
$env:TRIAGE_AI_MODE = 'openai'
.\mvnw.cmd spring-boot:run
```

3. 別のPowerShellでプレビューを作成します。この段階ではOpenAIへ送りません。

```powershell
$previewBody = @{ symptom = 'HTTP 500'; log = 'Duplicate entry synthetic-key' } | ConvertTo-Json
$preview = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/previews' -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($previewBody)) -SessionVariable triageSession
$preview | ConvertTo-Json -Depth 10
```

4. 表示されたマスキング済み内容と送信先を確認し、5分以内に以下を実行します。ここで外部通信・課金が発生します。

```powershell
$analysisBody = @{ preview_id = $preview.preview_id; execution_id = [guid]::NewGuid().ToString() } | ConvertTo-Json
$result = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/analyses' -Method Post -ContentType 'application/json' -Body $analysisBody -WebSession $triageSession
$result | ConvertTo-Json -Depth 20
```

5. 検証済みresultが返り、再実行が拒否されることを確認します。失敗時も新しいプレビューが必要です。
6. サーバーを停止し、起動側の環境変数を `Remove-Item Env:OPENAI_API_KEY, Env:TRIAGE_AI_MODE` で削除します。
