# Plugnostics-Hub 0.2.0 — logs locais

Plugin Paper / Java 21. Esta versão funciona sem token, sem painel web e sem enviar logs para serviços externos. Mesmo configurações antigas com `github.enabled: true` são desativadas na inicialização. Arquivos de relatórios e credenciais antigos são preservados, mas não são usados.

## Instalação

1. Pare o servidor e substitua o JAR antigo por `Plugnostics-Hub-0.2.0.jar` (mantenha somente uma versão).
2. Mantenha a pasta de configurações existente; as novas opções usam valores padrão sem exigir apagar o config.yml.
3. Inicie o servidor. Os logs são atualizados aproximadamente a cada segundo em `plugins/PlugnosticsHub/logs/`.

| Arquivo | Conteúdo |
| --- | --- |
| `plugins/NomeDoPlugin.log` | Registros cujo prefixo/logger identifica o plugin, incluindo continuação de stack traces |
| `users.log` | Todos os jogadores juntos: tentativas e resultado de login, entrada, saída, chat e nomes dos comandos |
| `errors/paper-errors.log` | ERROR/SEVERE/FATAL do servidor ou sem plugin identificável, com continuação dos stack traces |
| `errors/plugins-errors.log` | ERROR/SEVERE/FATAL atribuídos a plugins; também permanecem no log do próprio plugin |
| `server.log` | Console sem atribuição a plugin, incluindo avisos e mensagens gerais |
| `cursor.properties` | Posição de leitura interna; não editar enquanto o servidor estiver ligado |

Os arquivos são criados quando recebem registros. Um arquivo por plugin, sem separar por jogador. O arquivo ativo é `.log`; por padrão, ao atingir aproximadamente 10 MB, passa a `.log.1`, até cinco arquivos históricos por categoria. O tamanho pode ultrapassar o limite pelo tamanho de um lote. A leitura de `latest.log` tem limite de 1 MB por ciclo e recupera o atraso nos próximos ciclos, sem descartar o início do log por excesso de volume.

## Jogadores

O `users.log` usa eventos Paper/Bukkit a partir da ativação do plugin. `LOGIN_ATTEMPT`/`LOGIN_RESULT` não são garantia de entrada: `JOIN` confirma a entrada. Chat cancelado recebe `CHAT_CANCELLED`. Não captura mensagens privadas internas de outros plugins nem conversas enviadas antes da ativação. Nomes e textos são preservados; IPs IPv4 e UUIDs são ocultados por padrão. Argumentos de todos os comandos são omitidos para evitar registrar senhas de `/login` e `/register`. Não há registro de movimentação, inventários ou blocos.

## Limitações de atribuição

Os logs dos plugins são extraídos do `logs/latest.log` do Paper. Plugins que escrevem apenas em arquivos próprios não são coletados. Registros com tag desconhecida permanecem em `server.log`. Um erro em `paper-errors.log` pode ter origem em plugin sem identificação: a classificação não comprova a causa. Não há interceptação de stderr externo à JVM nem recuperação de um crash antes de o sistema gravar seu log.

A primeira ativação importa o `latest.log` existente; o cursor evita repetir linhas após recarregar o plugin no mesmo arquivo e reinicia quando o arquivo é substituído ou truncado. Queda abrupta durante a gravação pode repetir o último lote. Eventos de usuários têm fila limitada a 10.000 registros; descarte por sobrecarga é contabilizado no arquivo. Uma falha de disco não tem garantia de recuperação dos eventos em memória. Desligamento normal aguarda até cinco segundos para gravar a fila.

Ocultação de tokens e senhas é baseada em padrões e não garante remover todos os dados sensíveis. A ocultação de IPs cobre IPv4, não todos os formatos IPv6. Revise os arquivos antes de compartilhá-los. Os logs originais do Paper continuam intactos.

## Compilação e validação

`mvn -B -ntp clean package` com Java 21. Testes verificam separação de plugins/erros, continuação de stack trace, UTF-8 dividido entre leituras, cursor após reinício, truncamento e rotação. Ainda requer teste real no servidor Paper.
