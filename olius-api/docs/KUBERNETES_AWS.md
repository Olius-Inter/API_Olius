# Core API no Kubernetes da AWS Academy

Esta etapa prepara arquivos versionáveis sem credenciais. A aplicação no EKS é
manual, seguindo o fluxo do guia DEPLOY_AWS_APIS_E_IA.md: ECR → EKS → kubectl
apply → Service LoadBalancer. Os comandos abaixo partem da pasta `olius-api`.
Não há criação de banco, execução de SQL ou alteração de dados neste pacote.

## 1. Preparação manual no Learner Lab

1. Inicie o lab, abra o Console e selecione `us-east-1`.
2. Configure as credenciais temporárias da AWS CLI, incluindo o session token,
   fora do repositório. Confira com `aws sts get-caller-identity`.
3. Conforme a Parte 0, opção B do guia, crie `crud-lab` pelo Console, com Auto
   Mode desligado e os papéis permitidos pelo laboratório, como `LabRole`.
   Não use a opção de conta comum que cria papéis IAM com eksctl.
4. Configure as subnets públicas com a tag `kubernetes.io/role/elb=1`, crie o
   node group e aguarde dois nós Ready. Confira as permissões reais do lab.
5. Caso o PostgreSQL seja hospedado no próprio cluster, siga separadamente os
   passos de volume/EBS CSI e banco do guia. Estes manifests não o provisionam.
6. Confira a versão do cluster no Console. Instale um kubectl compatível:
   diferença máxima suportada de uma versão menor em relação ao servidor.
7. Conecte o terminal e confira o contexto antes de qualquer aplicação:

```powershell
aws eks update-kubeconfig --region us-east-1 --name crud-lab
kubectl config current-context
kubectl get nodes
```

O kubectl encontrado na preparação era 1.28.2; não assuma compatibilidade com
um novo cluster. Referência: https://kubernetes.io/releases/version-skew-policy/.

## 2. Imagem e configuração

Publique manualmente `olius-core:v1` no ECR conforme a seção 1.3 do guia.
Em `k8s/kustomization.yaml`, substitua `ACCOUNT_ID` pelo número da conta.
Use uma tag nova em cada atualização e registre-a em `newTag`.

Edite `k8s/10-core-config.yaml`:

- `DB_URL`: endereço do banco existente acessível pelos nós do EKS.
  O exemplo `postgres:5432/olius` só funciona com um Service `postgres` no
  namespace `olius`. O banco local no seu PC não fica acessível automaticamente;
  `localhost` e `host.docker.internal` não representam seu PC dentro do EKS.
- `DB_USER`: usuário limitado da Core, com as permissões já preparadas.
- `OLIUS_ALLOWED_ORIGINS`: origem exata do site, quando definida.

Não coloque senha na URL ou no ConfigMap. O perfil prod exige chaves persistentes
e mantém cookies Secure. SMTP e pagamentos estão desabilitados nesta configuração
inicial; não habilite sem configurar suas variáveis e Secrets correspondentes.

## 3. Secrets criados manualmente

Mantenha o arquivo real de senhas fora do repositório, conforme o guia original.
Exemplo de conteúdo do arquivo externo, preenchido por você:

```properties
CORE_DB_PASSWORD=preencha-com-a-senha-real
```

É um arquivo para `kubectl --from-env-file`, não para Compose: não acrescente
aspas envolvendo o valor. Não use senhas de exemplo nem compartilhe a saída dos
Secrets. O namespace precisa existir antes de criar os Secrets:

```powershell
kubectl apply -f k8s/00-namespace.yaml
kubectl create secret generic olius-secrets -n olius --from-env-file="C:\CAMINHO_FORA_DO_REPOSITORIO\core-secrets.env"
kubectl create secret generic core-jwt -n olius --from-file=jwt-private.pem=secrets/jwt-private.pem --from-file=jwt-public.pem=secrets/jwt-public.pem
```

Se `olius-secrets` já existir por causa da Redis API ou IA, não o apague nem o
substitua: confira se a chave `CORE_DB_PASSWORD` já está presente e atualize
somente essa chave, se necessário, pela edição manual autorizada.
Preserve as outras chaves. Não gere novamente as chaves JWT existentes: isso
invalidaria sessões. Os Secrets não são gerados pelo Kustomize nem versionados.

## 4. Renderizar, validar e aplicar

Renderização local, sem contato com AWS:

```powershell
kubectl kustomize k8s
```

Antes de aplicar, confirme que não resta `ACCOUNT_ID`, que o banco está acessível
e preparado e que os dois Secrets existem. Com o contexto correto e o cluster
disponível, valide o schema e a admissão no servidor sem persistir recursos:

```powershell
kubectl apply --dry-run=server -k k8s
```

O dry-run depende do namespace já criado. Ele não comprova que a imagem existe,
que a senha funciona nem que o banco está acessível. Então aplique manualmente:

```powershell
kubectl apply -k k8s
kubectl rollout status deployment/core -n olius --timeout=180s
kubectl get pods -n olius
kubectl logs -n olius deployment/core --tail=100
kubectl get svc core -n olius
```

A readiness TCP segue o guia e verifica apenas se a porta está aberta.
Teste também a consulta pública, que acessa o banco:

```powershell
curl.exe -i http://HOST_DO_LOAD_BALANCER/api/v1/establishment-types
```

O Service HTTP segue o laboratório. Para integrar autenticação web em produção,
configure HTTPS antes: cookies Secure não funcionam em HTTP e um site HTTPS
não deve chamar API HTTP. O tipo de Load Balancer depende do controller instalado
no cluster; o manifesto não instala um controller nem configura certificado.
Referência: https://docs.aws.amazon.com/eks/latest/userguide/network-load-balancing.html.
Se o endereço ficar Pending, confira eventos do Service, subnets e permissões.

## 5. Atualizações e encerramento

Mudou ConfigMap ou Secret usado como variável? Após aplicar a alteração, reinicie
manualmente com `kubectl rollout restart deployment/core -n olius` e confira o
rollout. Alterar ConfigMap não reinicia os pods automaticamente.

Para remover somente os recursos da Core, sem apagar o banco ou o namespace:

```powershell
kubectl delete -f k8s/20-core.yaml
kubectl delete -f k8s/10-core-config.yaml
```

Não use `kubectl delete -k k8s` para essa limpeza: ele inclui o namespace e pode
apagar banco, PVCs e serviços compartilhados. Para encerrar todo o laboratório,
faça backup e siga a seção 4 do guia, removendo os Load Balancers, node group,
cluster e conferindo volumes remanescentes. End Lab sozinho não comprova limpeza.
