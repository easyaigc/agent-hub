import { list, object, string, type WireObject } from './runtime.js';

const opening = '<send_user_message_question_reply>';
const closing = '</send_user_message_question_reply>';

export interface DesktopQuestion {
  id: string;
  sourceItemId: string;
  title: string;
  options: string[];
}

// This is the inspected desktop projection, not a blocking app-server request.
export function desktopQuestions(items: unknown): DesktopQuestion[] {
  return list(items).flatMap((value) => {
    const item = object(value);
    if (item.type !== 'agentMessage' || !string(item.id)) return [];
    return list(item.questions).flatMap((value, index) => {
      const question = object(value);
      if (!string(question.title).trim()) return [];
      return [
        {
          id: JSON.stringify(['request_user_input_async', item.id, index]),
          sourceItemId: string(item.id),
          title: string(question.title),
          options: list(question.options).filter((v): v is string => typeof v === 'string'),
        },
      ];
    });
  });
}

export function desktopQuestionAnswers(items: unknown): Set<string> {
  const answered = new Set<string>();
  for (const value of list(items)) {
    const item = object(value);
    if (
      item.type !== 'userMessage' &&
      !(item.type === 'steeringUserMessage' && item.status === 'accepted')
    )
      continue;
    const content = list(item.type === 'userMessage' ? item.content : item.input);
    if (content.length !== 1 || object(content[0]).type !== 'text') continue;
    for (const reply of parseDesktopQuestionReply(string(object(content[0]).text)))
      answered.add(string(reply.questionItemId));
  }
  return answered;
}

export function parseDesktopQuestionReply(text: string): WireObject[] {
  text = text.trim();
  if (!text.startsWith(opening + '\n') || !text.endsWith('\n' + closing)) return [];
  try {
    const parsed: unknown = JSON.parse(text.slice(opening.length, -closing.length).trim());
    return (Array.isArray(parsed) ? parsed : [parsed])
      .map(object)
      .filter(
        (reply) =>
          typeof reply.questionItemId === 'string' &&
          typeof reply.question === 'string' &&
          typeof reply.answer === 'string',
      );
  } catch {
    return [];
  }
}

export function desktopQuestionReply(question: DesktopQuestion, answers: string[]): string {
  const reply: WireObject = {
    questionItemId: question.id,
    question: question.title,
    answer: answers.join('\n'),
  };
  return `${opening}\n${JSON.stringify([reply])}\n${closing}`;
}
