#!/bin/bash
# 코드를 실행하지 않고 '버전이 실제로 존재하고 서로 맞는지'만 레지스트리에서 확인한다. 근거표: docs/23_개발착수_버전호환표.md
# 필요: curl, python3, node/npm. 네트워크: repo1.maven.org, registry.npmjs.org, pub.dev, storage.googleapis.com, services.gradle.org
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; FAIL=0
ok(){ echo "  ✔ $*"; }; ng(){ echo "  ✘ $*"; FAIL=1; }
mvn_exists(){ for t in 1 2 3 4; do c=$(curl -s -o /dev/null -w "%{http_code}" -L "https://repo1.maven.org/maven2/$1"); [ "$c" != 429 ] && break; python3 -c "import time;time.sleep(4)"; done; [ "$c" = 200 ]; }
echo "[1] Maven 좌표(Spring Boot 4.1.2 기준)"
for u in org/springframework/boot/spring-boot-gradle-plugin/4.1.2/spring-boot-gradle-plugin-4.1.2.pom \
  org/springframework/boot/spring-boot-starter-webmvc/4.1.2/spring-boot-starter-webmvc-4.1.2.pom \
  org/springframework/boot/spring-boot-starter-flyway/4.1.2/spring-boot-starter-flyway-4.1.2.pom \
  org/springframework/boot/spring-boot-starter-webmvc-test/4.1.2/spring-boot-starter-webmvc-test-4.1.2.pom \
  org/springframework/boot/spring-boot-testcontainers/4.1.2/spring-boot-testcontainers-4.1.2.pom \
  org/flywaydb/flyway-database-postgresql/12.4.0/flyway-database-postgresql-12.4.0.pom \
  org/testcontainers/testcontainers-postgresql/2.0.5/testcontainers-postgresql-2.0.5.pom \
  org/testcontainers/testcontainers-junit-jupiter/2.0.5/testcontainers-junit-jupiter-2.0.5.pom \
  org/springdoc/springdoc-openapi-starter-webmvc-ui/3.2.0/springdoc-openapi-starter-webmvc-ui-3.2.0.pom; do
  mvn_exists "$u" && ok "$(basename "$u" .pom)" || ng "없음/접속불가: $u"; done
echo "[2] web/package.json 범위가 실제 버전으로 해석되는지"
(cd "$ROOT/web" && node -e "
const p=require('./package.json');const all={...p.dependencies,...p.devDependencies};const {execSync}=require('child_process');let bad=0;
for(const [k,v] of Object.entries(all)){try{execSync('npm view '+k+'@\"'+v+'\" version --json',{stdio:'pipe',timeout:30000})}catch(e){console.log('  ✘',k,v);bad++}}
process.exit(bad?1:0)") && ok "web 의존성 범위 전부 해석됨" || ng "web 의존성 해석 실패"
echo "[3] Flutter 안정 버전 / pub 패키지 SDK 요구"
python3 - "$ROOT/app/pubspec.yaml" <<'PY' && ok "Flutter·pub 호환" || ng "Flutter·pub 불일치"
import json,re,sys,urllib.request
def get(u): return json.load(urllib.request.urlopen(u,timeout=20))
rel=get('https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json'); cur=[r for r in rel['releases'] if r['hash']==rel['current_release']['stable']][0]
print('   안정판 Flutter',cur['version'],'Dart',cur['dart_sdk_version'])
fl=tuple(map(int,cur['version'].split('.')[:2])); dart=tuple(map(int,cur['dart_sdk_version'].split('.')[:2])); bad=0
for name in re.findall(r'^  ([a-z_]+): \^',open(sys.argv[1]).read(),re.M):
    e=get(f'https://pub.dev/api/packages/{name}')['latest']['pubspec']['environment']
    m=re.search(r'\^(\d+)\.(\d+)',e.get('sdk','') or '')
    if m and (int(m[1]),int(m[2]))>dart: print('   ✘',name,'Dart 요구',e['sdk']); bad+=1
    f=re.search(r'>=(\d+)\.(\d+)',e.get('flutter','') or '')
    if f and (int(f[1]),int(f[2]))>fl: print('   ✘',name,'Flutter 요구',e['flutter']); bad+=1
sys.exit(1 if bad else 0)
PY
echo "[4] Gradle 래퍼 버전이 존재하는지"
v=$(sed -n 's/.*gradle-\([0-9.]*\)-bin.zip.*/\1/p' "$ROOT/backend/gradle/wrapper/gradle-wrapper.properties")
curl -s https://services.gradle.org/versions/all | python3 -c "import sys,json;sys.exit(0 if '$v' in [x['version'] for x in json.load(sys.stdin)] else 1)" && ok "Gradle $v 존재" || ng "Gradle $v 없음"
echo "[5] Docker 이미지 태그"
for t in library/postgres/tags/18 library/eclipse-temurin/tags/25-jdk library/eclipse-temurin/tags/25-jre library/caddy/tags/2 axllent/mailpit/tags/latest; do
  [ "$(curl -s -o /dev/null -w '%{http_code}' https://hub.docker.com/v2/repositories/$t)" = 200 ] && ok "$t" || ng "$t"; done
[ $FAIL = 0 ] && echo "ALL PASS" || { echo "FAILED"; exit 1; }
