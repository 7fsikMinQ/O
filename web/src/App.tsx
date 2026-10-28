import { useQuery } from '@tanstack/react-query';

type Features = Record<string, boolean>;

/** 뼈대 확인용 화면: 백엔드 /features 를 불러 돈·법무 기능이 꺼져 있는지 보여 준다. 실제 화면은 09번 문서 SCR-C* 를 따른다. */
export function App() {
  const { data, isPending, isError } = useQuery({
    queryKey: ['features'],
    queryFn: async (): Promise<Features> => {
      const res = await fetch('/api/features');
      if (!res.ok) throw new Error('features');
      return res.json();
    },
  });

  return (
    <main style={{ padding: 24 }}>
      <h1>보조출연 헬프 · 업체 웹</h1>
      {isPending && <p>불러오는 중이에요…</p>}
      {isError && <p>서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.</p>}
      {data && (
        <ul>
          {Object.entries(data).map(([code, on]) => (
            <li key={code}>
              {code}: {on ? '켜짐' : '꺼짐'}
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
