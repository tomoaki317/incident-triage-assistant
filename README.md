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

AI接続、画面、プレビューのID・期限・短期メモリ管理、公開デモのサンプル受付、RAG、認証、履歴保存は未実装です。
送信先は未選定と表示し、外部通信は行いません。トークン上限はモデル選定時に実装します。
障害元の想定はSpring Boot＋MySQLのREST APIですが、本ツールのテストにDBは不要です。

## テスト

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
